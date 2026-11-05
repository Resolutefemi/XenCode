package com.xencode.feature.agent.domain.provider

import com.xencode.core.util.AILogger
import com.xencode.feature.agent.data.remote.gemini.GeminiApi
import com.xencode.feature.agent.domain.model.AgentImage
import com.xencode.feature.agent.domain.model.AgentMessage
import com.xencode.feature.agent.domain.tool.AgentTool
import com.xencode.feature.agent.domain.tool.ToolCall
import com.xencode.feature.agent.domain.tool.modelToolResultText
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.IOException

class GeminiAdapter @Inject constructor(
    private val api: GeminiApi
) : AIProvider {

    override var apiKey = ""
    override var keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? = null
    override var baseUrl = "https://generativelanguage.googleapis.com/"
    override var useFullUrl = false
    override var useResponseApi = false
    override var model = "gemini-1.5-flash"
    override var providerId = ""
    override var logSessionId: String? = null
    override var firstByteTimeoutMs: Long = FIRST_BYTE_TIMEOUT_MS
    override var streamIdleTimeoutMs: Long = 0L
    override var maxNetworkRetries: Int = MAX_NETWORK_RETRIES

    /** Custom request headers: written out after placeholder substitution, fully overriding same-named default headers. */
    override var customHeaders: Map<String, String> = emptyMap()

    // Gemini sends generationConfig.maxOutputTokens (the model metadata output limit); when null the parameter is omitted and the server default applies.
    override var maxOutputTokens: Int? = null

    // Native generateContent omits generationConfig.temperature: from Gemini 2.0 on, the server default is already 1.0, matching the recommended value.
    override var temperature: Float? = null

    private fun extraHeaders(): Map<String, String> =
        resolveCustomHeaders(customHeaders, logSessionId, apiKey)

    /**
     * Target endpoint of the Interactions API. Two differences from generateContent:
     * the model name goes in the request body (so there is no "baseUrl ends with the model name" special case), and streaming and non-streaming use **the same endpoint**,
     * toggled by `?alt=sse` + request body `stream: true`. With useFullUrl the user's full baseUrl is used as-is.
     */
    private fun resolveInteractionsUrl(stream: Boolean): String {
        if (useFullUrl) return baseUrl
        val url = joinUrl(baseUrl, "v1beta/interactions")
        return if (stream) "$url?alt=sse" else url
    }

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        if (useResponseApi) {
            return completeViaInteractions(systemPrompt, messages, tools, reasoningEffort)
        }

        val request = buildRequestBody(systemPrompt, messages, tools, reasoningEffort)

        val url = if (useFullUrl) {
            baseUrl
        } else {
            val path = if (baseUrl.trimEnd('/').endsWith(model)) {
                baseUrl.trimEnd('/') + ":generateContent"
            } else {
                joinUrl(baseUrl, "v1beta/models/$model:generateContent")
            }
            path
        }
        val seq = AILogger.logRequest(logSessionId, "Gemini", model, "POST", url, request)

        val triedKeys = mutableSetOf(apiKey)
        val response = try {
            retryStaircase(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, _ -> switchKeyOnFailure(e, triedKeys) != null }
            ) {
                api.generateContent(url = url, apiKey = apiKey, extraHeaders = extraHeaders(), request = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Gemini", enriched, seq)
            throw enriched
        }
        AILogger.logResponse(logSessionId, "Gemini", response, seq)

        var contentText = ""
        var thinkingText = ""
        val toolCalls = mutableListOf<ToolCall>()
        val images = mutableListOf<AgentImage>()
        var finishReason: String? = null
        var partsSnapshot: String? = null

        // Non-streaming response field types occasionally vary (candidates missing or empty on a
        // content-safety block, finishReason/text possibly JSON null); Gson's getAsJsonArray/getAsJsonObject/asString throw outright in those cases.
        // Aligned with the streaming path (:208-218): every field guarded by takeIf { !it.isJsonNull } and the whole block wrapped in runCatching,
        // so a bad response yields an explicit result with a stopReason for upper layers instead of throwing a parse error at the user.
        val parseFailure = runCatching {
            val candidates = response.get("candidates")?.takeIf { it.isJsonArray }?.asJsonArray
            candidates?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.let { candidate ->
                finishReason = candidate.get("finishReason")?.takeIf { !it.isJsonNull }?.asString
                val content = candidate.get("content")?.takeIf { it.isJsonObject }?.asJsonObject
                val parts = content?.get("parts")?.takeIf { it.isJsonArray }?.asJsonArray
                parts?.forEach { partEl ->
                    val part = partEl.asJsonObject
                    val isThought = part.get("thought")?.takeIf { !it.isJsonNull }?.asBoolean == true
                    if (part.has("text")) {
                        val text = part.get("text")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        if (isThought) thinkingText += text else contentText += text
                    }
                    part.inlineImagePart()?.let { images.add(it) }
                    if (part.has("functionCall")) {
                        val fnCall = part.get("functionCall")?.takeIf { it.isJsonObject }?.asJsonObject
                        val name = fnCall?.get("name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        // The server supplies an id for parallel calls; fall back to the function name only when missing (calling the same tool twice would collide).
                        val callId = fnCall?.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: name
                        val argsStr = fnCall?.get("args")?.takeIf { it.isJsonObject }?.asJsonObject?.toString() ?: "{}"
                        val argsJson = parseArgs(argsStr)
                        toolCalls.add(ToolCall(id = callId, name = name, arguments = argsJson))
                    }
                }
                partsSnapshot = snapshotOf(parts)
            }

            // Content-safety block: candidates empty but promptFeedback carries a blockReason (SAFETY / PROHIBITED_CONTENT
            // etc.); surface it as the stopReason so upper layers show why it aborted instead of silently returning a blank body.
            if (finishReason == null && (candidates == null || candidates.size() == 0)) {
                response.get("promptFeedback")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("blockReason")?.takeIf { !it.isJsonNull }?.asString
                    ?.let { finishReason = it }
            }
        }.exceptionOrNull()

        val usageMetadata = response.get("usageMetadata")?.takeIf { it.isJsonObject }?.asJsonObject
        val inputTokens = usageMetadata?.get("promptTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0
        // candidatesTokenCount excludes thinking tokens, which are billed at the output rate, so the two added together are the true output.
        val outputTokens = (usageMetadata?.get("candidatesTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0) +
            (usageMetadata?.get("thoughtsTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0)
        val cachedInputTokens = usageMetadata?.get("cachedContentTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0

        // Response structure broken (parsing threw) with no displayable body/reason: return an explicit abort result instead of crashing;
        // stopReason=failed matches isAborted and upper layers will show an error message.
        val aborted = parseFailure != null && finishReason == null
        return AIResponse(
            content = contentText,
            toolCalls = toolCalls,
            stopReason = if (aborted) "failed" else finishReason,
            stopDetail = if (aborted) parseFailure?.message ?: "Failed to parse Gemini response" else null,
            reasoning = thinkingText.ifEmpty { null },
            thinkingBlocksJson = partsSnapshot,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cachedInputTokens = cachedInputTokens,
            images = images
        )
    }

    override fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): Flow<AIStreamChunk> = flow {
        if (useResponseApi) {
            streamViaInteractions(systemPrompt, messages, tools, reasoningEffort)
            return@flow
        }

        val request = buildRequestBody(systemPrompt, messages, tools, reasoningEffort)

        val url = if (useFullUrl) {
            baseUrl
        } else {
            val path = if (baseUrl.trimEnd('/').endsWith(model)) {
                baseUrl.trimEnd('/') + ":streamGenerateContent?alt=sse"
            } else {
                joinUrl(baseUrl, "v1beta/models/$model:streamGenerateContent?alt=sse")
            }
            path
        }
        
        val seq = AILogger.logRequest(logSessionId, "Gemini", model, "POST", url, request)
        val sseLog = AILogger.beginRawSse(logSessionId, "Gemini", seq)

        try {
            val triedKeys = mutableSetOf(apiKey)
            streamWithStaircaseRetry(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, canRetry ->
                    val outcome = switchKeyOnFailure(e, triedKeys)
                    if (outcome != null && canRetry) {
                        emit(AIStreamChunk.KeySwitched(outcome.newIndex, outcome.total))
                        true
                    } else false
                },
                attemptOnce = { onContent ->
                    withFirstContentTimeout(firstByteTimeoutMs) { firstContent ->
                val textBuilder = StringBuilder()
                val budget = StreamBudget()
                val toolCalls = mutableListOf<ToolCall>()
                val images = mutableListOf<AgentImage>()
                // Verbatim snapshot of the model turn's parts: text shards merged per segment, functionCall and thoughtSignature kept as-is.
                val snapshotParts = mutableListOf<JsonObject>()
                var currentFinishReason: String? = null
                // Stream hit end-of-stream before finishReason arrived (peer EOF / proxy dropped early / network switch);
                // if some text exists, deliver it degraded instead of failing the whole turn; see [logStreamTruncated].
                var truncatedByNetwork = false
                var sseLines = 0
                var streamInputTokens = 0
                var streamOutputTokens = 0
                var streamCachedInputTokens = 0

                val body = firstContent.awaitBody(api.streamGenerateContent(url = url, apiKey = apiKey, extraHeaders = extraHeaders(), request = request))

                body.use { rb ->
                    firstContent.attach { rb.close() }
                    val idleWatchdog = launchStreamIdleWatchdog(streamIdleTimeoutMs) { firstContent.closeBody() }
                    val closeHandle = coroutineContext[Job]?.invokeOnCompletion {
                        runCatching { rb.close() }
                    }
                    try {
                        val reader = rb.charStream().buffered()
                        // Breaking on finishReason ends the stream normally; readLine() returning null means the
                        // connection died before it arrived. Throw and let retry/logging take over only when no body has arrived; once text has streamed, deliver degraded
                        // (mark the output truncated and let the Agent loop auto-continue), avoiding a failed turn that wastes everything already streamed.
                        while (true) {
                            coroutineContext.ensureActive()
                            val line = reader.readLine()
                            if (line == null) {
                                if (!textBuilder.isSalvageableAfterTruncation()) {
                                    throw IOException("SSE stream interrupted (possible network drop)")
                                }
                                truncatedByNetwork = true
                                logStreamTruncated("Gemini", textBuilder.length, sseLines)
                                break
                            }
                            sseLines++
                            idleWatchdog.touch()
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data.isEmpty()) continue
                            sseLog.append(line)
                            val obj = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                            
                            try {
                                obj.get("usageMetadata")?.takeIf { it.isJsonObject }?.asJsonObject?.let { um ->
                                    streamInputTokens = um.get("promptTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: streamInputTokens
                                    // Thinking tokens are not in candidatesTokenCount but are billed at the output rate; the two added together are the true output.
                                    val candidateTokens = um.get("candidatesTokenCount")?.takeIf { !it.isJsonNull }?.asInt
                                    val thoughtTokens = um.get("thoughtsTokenCount")?.takeIf { !it.isJsonNull }?.asInt
                                    if (candidateTokens != null || thoughtTokens != null) {
                                        streamOutputTokens = (candidateTokens ?: 0) + (thoughtTokens ?: 0)
                                    }
                                    streamCachedInputTokens = um.get("cachedContentTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: streamCachedInputTokens
                                }
                                val chunkCandidates = obj.getAsJsonArray("candidates")
                                chunkCandidates?.firstOrNull()?.asJsonObject?.let { candidate ->
                                    val reason = candidate.get("finishReason")?.takeIf { !it.isJsonNull }?.asString
                                    if (reason != null && reason != "null") currentFinishReason = reason

                                    val content = candidate.getAsJsonObject("content")
                                    content?.getAsJsonArray("parts")?.forEach { partEl ->
                                        val part = partEl.asJsonObject
                                        val isThought = part.get("thought")?.asBoolean == true
                                        if (part.has("functionCall")) {
                                            firstContent.receivedContent()
                                            onContent()
                                            budget.add(part.toString())
                                        }
                                        accumulateSnapshotPart(snapshotParts, part, isThought)
                                        if (part.has("text")) {
                                            val text = part.get("text")?.asString ?: ""
                                            if (text.isNotEmpty()) {
                                                budget.add(text)
                                                if (isThought) {
                                                    // Reasoning delta: live UI display only, not added to the body (not persisted, so it can safely re-stream on retry)
                                                    firstContent.receivedContent()
                                                    onContent()
                                                    emit(AIStreamChunk.ReasoningDelta(text))
                                                } else {
                                                    textBuilder.append(text)
                                                    firstContent.receivedContent()
                                                    onContent()
                                                    emit(AIStreamChunk.TextDelta(text))
                                                }
                                            }
                                        }
                                        part.inlineImagePart()?.let { img ->
                                            images.add(img)
                                            firstContent.receivedContent()
                                            onContent()
                                        }
                                        if (part.has("functionCall")) {
                                            val fnCall = part.getAsJsonObject("functionCall")
                                            val name = fnCall.get("name")?.asString ?: ""
                                            // Notify the UI as soon as the tool name appears, swapping the generic "thinking" state for a concrete scene
                                            if (name.isNotEmpty()) emit(AIStreamChunk.ToolCallDeclared(name))
                                            val callId = fnCall.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: name
                                            val argsStr = fnCall.getAsJsonObject("args")?.toString() ?: "{}"
                                            val argsJson = parseArgs(argsStr)
                                            toolCalls.add(ToolCall(id = callId, name = name, arguments = argsJson))
                                        }
                                    }
                                }
                                if (currentFinishReason != null) break
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                coroutineContext.ensureActive()
                                // ignore
                            }
                        }
                    } finally {
                        idleWatchdog.cancel()
                        closeHandle?.dispose()
                    }
                }

                // When degrading after a broken stream, mark stopReason as length so the Agent loop auto-continues instead of failing the turn.
                val truncated = truncatedByNetwork && currentFinishReason == null
                emit(AIStreamChunk.Final(AIResponse(content = textBuilder.toString(), toolCalls = toolCalls, stopReason = if (truncated) STREAM_TRUNCATED_STOP_REASON else currentFinishReason, thinkingBlocksJson = snapshotOf(snapshotParts), inputTokens = streamInputTokens, outputTokens = streamOutputTokens, cachedInputTokens = streamCachedInputTokens, images = images)))
                    }
                },
                onRetry = { attempt, max, error -> emit(AIStreamChunk.Retrying(attempt, max, error)) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Gemini", enriched, seq)
            throw enriched
        } finally {
            sseLog.finish()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Interactions request body. Differences from generateContent in four places:
     * - `system_instruction` is a **top-level string**, no longer a `{role, parts}` object;
     * - history is a flat step sequence (see [buildInteractionsInput]) and the model name moves into the body;
     * - tool declarations are flat, no more `functionDeclarations` wrapper;
     * - thinking only has `thinking_level` (the 2.5-series `thinkingBudget` does not exist here).
     *
     * Always sends `store=false` for stateless mode: the local messages list is the single source of truth, and context
     * compression, regeneration, and failed-request retries all rely on the client holding the full history; server-side state would clash with them directly.
     * `temperature` is not sent: Interactions' GenerationConfig has no such field.
     */
    private fun buildInteractionsRequest(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?,
        stream: Boolean
    ): Map<String, Any?> {
        val request = mutableMapOf<String, Any?>(
            "model" to model,
            "input" to buildInteractionsInput(messages),
            "store" to false
        )
        if (systemPrompt.isNotBlank()) request["system_instruction"] = systemPrompt
        buildInteractionsTools(tools)?.let { request["tools"] = it }
        if (stream) request["stream"] = true

        val generationConfig = mutableMapOf<String, Any>()
        maxOutputTokens?.takeIf { it > 0 }?.let { generationConfig["max_output_tokens"] = it }
        interactionsThinkingLevel(reasoningEffort)?.let {
            generationConfig["thinking_level"] = it
            // Without explicitly requesting summaries we would only get the thinking signature, never displayable thinking text, and the UI's reasoning pane would stay empty.
            // Sent together with thinking_level: non-thinking models (gemma / image / music) should not receive either field.
            generationConfig["thinking_summaries"] = "auto"
        }
        if (generationConfig.isNotEmpty()) request["generation_config"] = generationConfig
        return request
    }

    /** Reasoning effort -> `thinking_level`, which only supports minimal/low/medium/high; none skips (no thinking sent) and xhigh/max normalize to high. */
    private fun interactionsThinkingLevel(reasoningEffort: String?): String? = when (reasoningEffort) {
        null, "none" -> null
        "xhigh", "max" -> "high"
        else -> reasoningEffort
    }

    /**
     * Non-streaming Interactions request. Results are parsed from the `steps` timeline (see [parseInteractionSteps]),
     * and the stop reason is the interaction's `status` instead of `finishReason`: `incomplete` means the output limit was hit,
     * `requires_action` means tools await execution; see [interactionStopReason] for value normalization.
     */
    private suspend fun completeViaInteractions(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        val url = resolveInteractionsUrl(stream = false)
        val request = buildInteractionsRequest(systemPrompt, messages, tools, reasoningEffort, stream = false)
        val seq = AILogger.logRequest(logSessionId, "Gemini", model, "POST", url, request)

        val triedKeys = mutableSetOf(apiKey)
        val response = try {
            retryStaircase(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, _ -> switchKeyOnFailure(e, triedKeys) != null }
            ) {
                api.createInteraction(url = url, apiKey = apiKey, extraHeaders = extraHeaders(), request = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Gemini", enriched, seq)
            throw enriched
        }
        AILogger.logResponse(logSessionId, "Gemini", response, seq)

        val parsed = parseInteractionSteps(response.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray)
        val usage = parseInteractionsUsage(response.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject)
        val status = response.get("status")?.takeIf { it.isJsonPrimitive }?.asString
        // When status=failed the body is usually empty and the reason lives only in errors; extract it for upper layers to display.
        val errorDetail = response.get("errors")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("message")?.takeIf { it.isJsonPrimitive }?.asString

        return AIResponse(
            content = parsed.text,
            toolCalls = parsed.toolCalls,
            stopReason = interactionStopReason(status),
            stopDetail = errorDetail,
            reasoning = parsed.reasoning.takeIf { it.isNotEmpty() },
            thinkingBlocksJson = parsed.stepsSnapshotJson,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cachedInputTokens = usage.cachedInputTokens,
            images = parsed.images
        )
    }

    /**
     * Streaming Interactions request. Unlike generateContent's candidate shards, this is a semantic event stream
     * (see [GeminiInteractionsStreamAccumulator]) with no `[DONE]`: completion is judged by the interaction's
     * status reaching a terminal state; only an unterminated read to end-of-stream counts as truncated.
     */
    private suspend fun FlowCollector<AIStreamChunk>.streamViaInteractions(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ) {
        val url = resolveInteractionsUrl(stream = true)
        val request = buildInteractionsRequest(systemPrompt, messages, tools, reasoningEffort, stream = true)
        val seq = AILogger.logRequest(logSessionId, "Gemini", model, "POST", url, request)
        // Raw SSE is flushed to disk in batches (see AILogger.beginRawSse); finalized in finally at end of turn.
        val sseLog = AILogger.beginRawSse(logSessionId, "Gemini", seq)
        try {
            val triedKeys = mutableSetOf(apiKey)
            streamWithStaircaseRetry(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, canRetry ->
                    val outcome = switchKeyOnFailure(e, triedKeys)
                    if (outcome != null && canRetry) {
                        emit(AIStreamChunk.KeySwitched(outcome.newIndex, outcome.total))
                        true
                    } else false
                },
                attemptOnce = { onContent ->
                    withFirstContentTimeout(firstByteTimeoutMs) { firstContent ->
                    val acc = GeminiInteractionsStreamAccumulator()

                    val body = firstContent.awaitBody(api.streamInteraction(
                        url = url,
                        apiKey = apiKey,
                        extraHeaders = extraHeaders(),
                        request = request
                    ))

                    body.use { rb ->
                        firstContent.attach { rb.close() }
                        val idleWatchdog = launchStreamIdleWatchdog(streamIdleTimeoutMs) { firstContent.closeBody() }
                        val closeHandle = coroutineContext[Job]?.invokeOnCompletion {
                            runCatching { rb.close() }
                        }
                        try {
                            val reader = rb.charStream().buffered()
                            var sseLines = 0
                            // Same policy as generateContent: the connection died before a terminal state; throw and let retry
                            // take over only when no body has arrived; once text has streamed, deliver degraded (mark truncated; the Agent loop continues).
                            while (!acc.terminated) {
                                coroutineContext.ensureActive()
                                val line = reader.readLine()
                                if (line == null) {
                                    if (acc.streamedTextLength <= 0) {
                                        throw IOException("SSE stream interrupted: interaction never reached a terminal state (possible network drop)")
                                    }
                                    acc.markTruncatedByNetwork()
                                    logStreamTruncated("Gemini/Interactions", acc.streamedTextLength, sseLines)
                                    break
                                }
                                sseLines++
                                idleWatchdog.touch()
                                if (!line.startsWith("data:")) continue
                                val data = line.removePrefix("data:").trim()
                                if (data.isEmpty()) continue
                                sseLog.append(line)
                                if (data == "[DONE]") break
                                val obj = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                                // A field-type error in one event must not kill the stream; skip only that event,
                                // but StreamApiException (error events) and cancellation signals must be rethrown.
                                val delta = try {
                                    acc.accept(obj).also {
                                        if (acc.receivedContent) {
                                            firstContent.receivedContent()
                                            onContent()
                                        }
                                    }
                                } catch (e: StreamApiException) {
                                    throw e
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    coroutineContext.ensureActive()
                                    null
                                }
                                when (delta) {
                                    is InteractionsDelta.Text -> {
                                        firstContent.receivedContent()
                                        onContent()
                                        emit(AIStreamChunk.TextDelta(delta.text))
                                    }
                                    // Reasoning deltas are UI display only and never added to the body; receiving one proves the connection is live.
                                    is InteractionsDelta.Reasoning -> {
                                        firstContent.receivedContent()
                                        onContent()
                                        emit(AIStreamChunk.ReasoningDelta(delta.text))
                                    }
                                    // The tool name arrives before the arguments: notify the UI to switch to a concrete scene early
                                    is InteractionsDelta.ToolCallDeclared -> {
                                        firstContent.receivedContent()
                                        onContent()
                                        emit(AIStreamChunk.ToolCallDeclared(delta.name))
                                    }
                                    null -> {}
                                }
                            }
                        } finally {
                            idleWatchdog.cancel()
                            closeHandle?.dispose()
                        }
                    }

                    emit(AIStreamChunk.Final(acc.toResponse()))
                    }
                },
                onRetry = { attempt, max, error -> emit(AIStreamChunk.Retrying(attempt, max, error)) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Gemini", enriched, seq)
            throw enriched
        } finally {
            sseLog.finish()
        }
    }

    /**
     * Request body shared by generateContent / streamGenerateContent.
     * thinkingConfig and maxOutputTokens must be written into `generationConfig` together (two separate assignments would overwrite each other).
     */
    private fun buildRequestBody(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): MutableMap<String, Any> {
        val request = mutableMapOf<String, Any>(
            "contents" to convertToGeminiContents(messages)
        )
        if (systemPrompt.isNotBlank()) {
            request["systemInstruction"] = mapOf(
                "role" to "system",
                "parts" to listOf(mapOf("text" to systemPrompt))
            )
        }
        tools.takeIf { it.isNotEmpty() }?.map { tool ->
            mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "parameters" to tool.toJsonSchema()
            )
        }?.let { request["tools"] = listOf(mapOf("functionDeclarations" to it)) }

        val generationConfig = mutableMapOf<String, Any>()
        buildThinkingConfig(reasoningEffort)?.let { generationConfig["thinkingConfig"] = it }
        maxOutputTokens?.takeIf { it > 0 }?.let { generationConfig["maxOutputTokens"] = it }
        if (generationConfig.isNotEmpty()) request["generationConfig"] = generationConfig
        return request
    }

    /**
     * Verbatim snapshot of the model turn's parts; generated only when content that must be replayed verbatim exists (thoughtSignature or functionCall);
     * pure-text turns return null so history takes the regular rebuild path (avoiding double-writing the body).
     */
    private fun snapshotOf(parts: com.google.gson.JsonArray?): String? {
        if (parts == null || parts.size() == 0) return null
        val needsSnapshot = parts.any { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@any false
            o.has("functionCall") || o.has("thoughtSignature") || o.has("inlineData")
        }
        return if (needsSnapshot) parts.toString() else null
    }

    private fun snapshotOf(parts: List<JsonObject>): String? {
        if (parts.isEmpty()) return null
        val array = com.google.gson.JsonArray().apply { parts.forEach { add(it) } }
        return snapshotOf(array)
    }

    /**
     * Merge one streamed part into the snapshot: functionCall becomes its own verbatim segment; text shards merge per "thought or not",
     * and a shard's thoughtSignature is written back to its segment (the signature is part-level metadata that arrives on only one shard).
     */
    /** Image part (camelCase inlineData / snake_case inline_data) -> [AgentImage]; images emitted directly by image models arrive whole. */
    private fun JsonObject.inlineImagePart(): AgentImage? =
        (get("inlineData") ?: get("inline_data"))?.takeIf { it.isJsonObject }?.asJsonObject?.let { inline ->
            val data = inline.get("data")?.takeIf { !it.isJsonNull }?.asString
            if (data.isNullOrBlank()) null
            else AgentImage(
                mimeType = inline.get("mimeType")?.takeIf { !it.isJsonNull }?.asString
                    ?: inline.get("mime_type")?.takeIf { !it.isJsonNull }?.asString
                    ?: "image/png",
                base64Data = data
            )
        }

    private fun accumulateSnapshotPart(snapshot: MutableList<JsonObject>, part: JsonObject, isThought: Boolean) {
        if (part.has("functionCall")) {
            snapshot.add(part.deepCopy())
            return
        }
        if (!part.has("text")) {
            // Images arrive whole with no text shard to merge; they enter the snapshot verbatim for replay in the next editing turn (including SynthID and other metadata).
            if (part.has("thoughtSignature") || part.has("inlineData")) snapshot.add(part.deepCopy())
            return
        }
        val last = snapshot.lastOrNull()?.takeIf { prev ->
            !prev.has("functionCall") &&
                prev.has("text") &&
                (prev.get("thought")?.asBoolean == true) == isThought
        }
        if (last != null) {
            last.addProperty("text", last.get("text").asString + (part.get("text")?.asString ?: ""))
            part.get("thoughtSignature")?.takeIf { !it.isJsonNull }?.let { last.add("thoughtSignature", it) }
        } else {
            snapshot.add(part.deepCopy())
        }
    }

    /** Restore the snapshot into a parts array; returns null when blank or corrupt, and the caller falls back to rebuilding. */
    private fun decodeSnapshotParts(json: String): com.google.gson.JsonArray? {
        if (json.isBlank()) return null
        return runCatching {
            JsonParser.parseString(json).asJsonArray.takeIf { it.size() > 0 }
        }.getOrNull()
    }

    /** Reasoning effort -> Gemini thinkingConfig. Models with gemini-3 in the name use thinkingLevel, others thinkingBudget (2.5 series). includeThoughts is set explicitly so thinking content is returned. */
    private fun buildThinkingConfig(reasoningEffort: String?): Map<String, Any>? {
        if (reasoningEffort == null) return null
        return if (model.contains("gemini-3")) {
            // thinkingLevel only supports minimal/low/medium/high; none skips (no thinking sent) and xhigh/max normalize to high (when metadata misses, the UI offers all levels)
            val level = when (reasoningEffort) {
                "none" -> return null
                "xhigh", "max" -> "high"
                else -> reasoningEffort
            }
            mapOf("thinkingLevel" to level, "includeThoughts" to true)
        } else {
            val budget = when (reasoningEffort) {
                "low" -> 1024
                "medium" -> 4096
                "high", "xhigh", "max" -> 8192
                else -> return null
            }
            mapOf("thinkingBudget" to budget, "includeThoughts" to true)
        }
    }

    private fun parseArgs(raw: String): kotlinx.serialization.json.JsonObject {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return kotlinx.serialization.json.JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrElse { kotlinx.serialization.json.JsonObject(emptyMap()) }
    }

    private fun convertToGeminiContents(messages: List<AgentMessage>): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        // Defensive tracking: whether the previous assistant (i.e. model) message contained functionCall
        var lastModelHadFunctionCall = false
        // Multiple tool results of one model turn must be merged into a single user content: for parallel calls, Gemini and compatible
        // gateways all pair on "one turn returns all functionResponses"; splitting them is judged as missing tool results (HTTP 400).
        var pendingToolResponseParts: MutableList<Map<String, Any>>? = null

        for (message in messages) {
            when (message) {
                is AgentMessage.UserMessage -> {
                    val parts = mutableListOf<Map<String, Any>>()
                    if (message.content.isNotBlank()) {
                        parts.add(mapOf("text" to message.content))
                    }
                    message.images.forEach { image ->
                        parts.add(image.toGeminiInlineDataPart())
                    }
                    result.add(
                        mapOf(
                            "role" to "user",
                            "parts" to parts
                        )
                    )
                    lastModelHadFunctionCall = false
                    pendingToolResponseParts = null
                }
                is AgentMessage.AssistantMessage -> {
                    lastModelHadFunctionCall = message.toolCalls.isNotEmpty()
                    pendingToolResponseParts = null
                    // When the previous model output has a verbatim snapshot, replay it as-is: thoughtSignature is part-level metadata,
                    // rebuilding parts would lose the signature and Gemini 3 could not continue the previous turn's reasoning context.
                    val snapshot = decodeSnapshotParts(message.thinkingBlocksJson)
                    if (snapshot != null) {
                        result.add(mapOf("role" to "model", "parts" to snapshot))
                        continue
                    }
                    val parts = mutableListOf<Map<String, Any>>()
                    if (message.content.isNotEmpty()) {
                        parts.add(mapOf("text" to message.content))
                    }
                    for (toolCall in message.toolCalls) {
                        parts.add(
                            mapOf(
                                "functionCall" to mapOf(
                                    "name" to toolCall.name,
                                    "args" to toolCall.arguments
                                )
                            )
                        )
                    }
                    if (parts.isNotEmpty()) {
                        result.add(
                            mapOf(
                                "role" to "model",
                                "parts" to parts
                            )
                        )
                    }
                }
                is AgentMessage.ToolResultMessage -> {
                    // Defensive cleanup: skip orphaned functionResponses with no matching functionCall
                    if (!lastModelHadFunctionCall) continue
                    val parts = pendingToolResponseParts ?: mutableListOf<Map<String, Any>>().also {
                        result.add(mapOf("role" to "user", "parts" to it))
                        pendingToolResponseParts = it
                    }
                    // File-type tools feed the model a condensed projection; the UI/persistence still use the full result.
                    val modelText = message.modelResult
                        ?: modelToolResultText(message.toolName, message.result)
                        ?: message.result
                    // name carries the function name; with parallel calls an id is also sent back for pairing (old data uses the function name as id, in which case no id is sent).
                    val functionName = message.toolName.ifBlank { message.id }
                    val functionResponse = mutableMapOf<String, Any>(
                        "name" to functionName,
                        "response" to mapOf("result" to modelText)
                    )
                    if (message.id.isNotBlank() && message.id != functionName) {
                        functionResponse["id"] = message.id
                    }
                    parts.add(mapOf("functionResponse" to functionResponse))
                    message.images.forEach { image ->
                        parts.add(image.toGeminiInlineDataPart())
                    }
                }
            }
        }
        return result
    }

    private fun AgentImage.toGeminiInlineDataPart(): Map<String, Any> {
        return mapOf(
            "inline_data" to mapOf(
                "mime_type" to mimeType,
                "data" to base64Data
            )
        )
    }
}
