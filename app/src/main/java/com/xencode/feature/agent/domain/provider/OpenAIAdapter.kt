package com.xencode.feature.agent.domain.provider

import com.xencode.core.util.AILogger
import com.xencode.feature.agent.data.remote.openai.OpenAIApi
import com.xencode.feature.settings.domain.model.ProviderType
import com.xencode.feature.settings.domain.model.defaultProviderApiPath
import java.io.IOException
import com.xencode.feature.agent.data.remote.openai.ChatCompletionRequest
import com.xencode.feature.agent.data.remote.openai.OpenAIChatMessage
import com.xencode.feature.agent.domain.model.AgentImage
import com.xencode.feature.agent.domain.model.AgentMessage
import com.xencode.feature.agent.domain.tool.AgentTool
import com.xencode.feature.agent.domain.tool.ToolCall
import com.xencode.feature.agent.domain.tool.modelToolResultText
import com.google.gson.JsonParser
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.xencode.feature.agent.data.remote.openai.OpenAIToolCall
import com.xencode.feature.agent.data.remote.openai.OpenAIToolDefinition
import com.xencode.feature.agent.data.remote.openai.OpenAIFunctionDefinition
import com.xencode.feature.agent.data.remote.openai.OpenAIImagePart
import com.xencode.feature.agent.data.remote.openai.StreamOptions

class OpenAIAdapter @Inject constructor(
    private val api: OpenAIApi
) : AIProvider {

    override var apiKey = ""
    override var keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? = null
    override var baseUrl = "https://api.openai.com/"
    override var useFullUrl = false
    override var useResponseApi = false
    override var model = "gpt-4-turbo"
    override var providerId = ""
    override var logSessionId: String? = null
    override var firstByteTimeoutMs: Long = FIRST_BYTE_TIMEOUT_MS
    override var streamIdleTimeoutMs: Long = 0L
    override var maxNetworkRetries: Int = MAX_NETWORK_RETRIES

    /**
     * Whether to send `prompt_cache_key` (cache shard routing) on the Chat Completion path.
     * Off by default: official OpenAI Chat Completion rejects unknown fields (400); only third-party compatible services (DeepInfra/Cerebras etc.) support it.
     * The Responses API path supports it natively and sends it unconditionally, unaffected by this toggle.
     */
    var chatCacheKeyEnabled: Boolean = false

    /** Custom request headers: written out after placeholder substitution, fully overriding same-named default headers. */
    override var customHeaders: Map<String, String> = emptyMap()

    // The OpenAI family sends no output-limit parameter (the server default applies); kept only for interface completeness.
    override var maxOutputTokens: Int? = null

    // When null, the request body omits temperature (Gson skips null fields) and the server default applies.
    override var temperature: Float? = null

    private fun extraHeaders(): Map<String, String> =
        resolveCustomHeaders(customHeaders, logSessionId, apiKey)

    /**
     * Target endpoint: `v1/responses` when the Responses API is enabled, otherwise the chat/completions path ([defaultProviderApiPath]).
     * With useResponseApi the request body is Responses-shaped (`input`); sending it to chat/completions would be rejected by the server with
     * "missing field `messages`", so the endpoint must switch in lockstep. With useFullUrl the user's full baseUrl is used as-is.
     */
    private fun resolveApiUrl(): String {
        if (useFullUrl) return baseUrl
        val path = if (useResponseApi) "v1/responses" else defaultProviderApiPath(ProviderType.OPENAI)
        return joinUrl(baseUrl, path)
    }

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        if (useResponseApi) return completeViaResponses(systemPrompt, messages, tools, reasoningEffort)

        val openAIMessages = buildList {
            if (systemPrompt.isNotBlank()) {
                add(OpenAIChatMessage(role = systemRoleForModel(), content = systemPrompt))
            }
            addAll(convertToOpenAIMessages(messages))
        }

        val toolDefs = tools.takeIf { it.isNotEmpty() }?.map { tool ->
            OpenAIToolDefinition(
                function = OpenAIFunctionDefinition(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.toJsonSchema()
                )
            )
        }

        val url = resolveApiUrl()
        val request = ChatCompletionRequest(
            model = model,
            messages = openAIMessages,
            temperature = temperature,
            reasoning_effort = normalizeReasoningEffort(reasoningEffort),
            tools = toolDefs,
            tool_choice = if (toolDefs != null) "auto" else null,
            stream = false,
            prompt_cache_key = if (chatCacheKeyEnabled) logSessionId else null
        )
        val seq = AILogger.logRequest(logSessionId, "OpenAI", model, "POST", url, request)

        val triedKeys = mutableSetOf(apiKey)
        val response = try {
            retryStaircase(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, _ -> switchKeyOnFailure(e, triedKeys) != null }
            ) {
                api.createChatCompletion(url = url, authorization = "Bearer $apiKey", extraHeaders = extraHeaders(), request = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "OpenAI", enriched, seq)
            throw enriched
        }
        AILogger.logResponse(logSessionId, "OpenAI", response, seq)

        val message = response.choices.firstOrNull()?.message
        val finishReason = response.choices.firstOrNull()?.finish_reason
        val content = message?.content.asTextContent()
        val images = message?.images?.mapNotNull { it.toAgentImage() } ?: emptyList()
        val toolCalls = message?.tool_calls?.map { convertToToolCall(it) } ?: emptyList()
        val reasoning = message?.reasoning_content?.takeIf { it.isNotEmpty() }
            ?: message?.reasoning?.takeIf { it.isNotEmpty() }
        val usage = response.usage

        return AIResponse(content = content, toolCalls = toolCalls, stopReason = finishReason, reasoning = reasoning, inputTokens = usage?.prompt_tokens ?: 0, outputTokens = usage?.completion_tokens ?: 0, cachedInputTokens = usage?.prompt_tokens_details?.cached_tokens ?: 0, cacheCreationTokens = usage?.prompt_tokens_details?.cache_write_tokens ?: 0, images = images)
    }

    /**
     * o-series reasoning models reject the system role and require developer instead.
     * Decided by prefix: o1/o3/o4 and later o-series (o5, o6...), plus the gpt-5 series (officially under the same
     * "reasoning model" role convention), all use developer. gpt-4 and earlier, and third-party compatible services
     * (deepseek/kimi/minimax/moonshot etc.), match no prefix, keep system, and are unaffected.
     */
    private fun systemRoleForModel(): String = when {
        model.startsWith("o", ignoreCase = true) -> "developer"
        model.startsWith("gpt-5", ignoreCase = true) -> "developer"
        else -> "system"
    }

    /**
     * Reasoning effort -> OpenAI reasoning_effort.
     * Official OpenAI Chat Completions reasoning_effort accepts only low/medium/high (the gpt-5 series additionally
     * supports none/minimal, but the o-series does not); passing "none"/"minimal" through verbatim 400s on most reasoning models.
     * Here "none"/"minimal" are explicitly skipped (the field is omitted and the server default applies) and "xhigh"/"max" normalize to high
     * (OpenAI has nothing higher), while low/medium/high pass through. Aligned with Anthropic/Gemini on the "skip when inexpressible" policy.
     */
    private fun normalizeReasoningEffort(effort: String?): String? = when (effort) {
        null -> null
        "none", "minimal" -> null
        "xhigh", "max" -> "high"
        else -> effort
    }

    /**
     * Responses request body. The system prompt enters `input` as the first message item (equivalent to the
     * top-level `instructions`, which the server likewise treats as the first system message), so `instructions` is not sent separately.
     */
    private fun buildResponsesRequest(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?,
        stream: Boolean
    ): Map<String, Any?> {
        val isDeepSeek = model.contains("deepseek", ignoreCase = true)
        val request = mutableMapOf<String, Any?>(
            "model" to model,
            "input" to buildResponsesInput(
                systemPrompt = systemPrompt,
                systemRole = systemRoleForModel(),
                messages = messages,
                // DeepSeek thinking mode: requests with tools must replay every historical turn's reasoning (same as
                // reasoning_content on the Chat Completions side); the official/generic path also replays automatically when a valid snapshot exists.
                includeReasoningItems = isDeepSeek
            )
        )
        buildResponsesTools(tools)?.let {
            request["tools"] = it
            request["tool_choice"] = "auto"
        }
        if (stream) request["stream"] = true
        normalizeReasoningEffort(reasoningEffort)?.let { effort ->
            if (isDeepSeek) {
                request["reasoning"] = mapOf("effort" to effort)
            } else {
                // OpenAI official/generic Responses spec: explicitly enable reasoning summaries (summary: "auto")
                // and request the encrypted_content ciphertext, for continuous reasoning across stateless multi-turn conversations.
                request["reasoning"] = mapOf("effort" to effort, "summary" to "auto")
                request["include"] = listOf("reasoning.encrypted_content")
            }
        }
        // Official OpenAI Responses supports prompt_cache_key natively (routes a session to the same cache shard);
        // services that manage caching automatically (e.g. DeepSeek) silently ignore the field without erroring.
        logSessionId?.let { request["prompt_cache_key"] = it }
        return request
    }

    /**
     * Non-streaming Responses API request. Three differences from Chat Completions: tool definition fields are flat,
     * history is an input item sequence (see [buildResponsesInput]), and results are parsed from top-level items of `output`
     * (tool calls are standalone function_call items, not inside an assistant message's content).
     */
    private suspend fun completeViaResponses(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        val url = resolveApiUrl()
        val request = buildResponsesRequest(systemPrompt, messages, tools, reasoningEffort, stream = false)
        val seq = AILogger.logRequest(logSessionId, "OpenAI", model, "POST", url, request)

        val triedKeys = mutableSetOf(apiKey)
        val response = try {
            retryStaircase(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, _ -> switchKeyOnFailure(e, triedKeys) != null }
            ) {
                api.createResponses(url = url, authorization = "Bearer $apiKey", extraHeaders = extraHeaders(), request = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "OpenAI", enriched, seq)
            throw enriched
        }
        AILogger.logResponse(logSessionId, "OpenAI", response, seq)

        val parsed = parseResponsesOutput(response.get("output")?.takeIf { it.isJsonArray }?.asJsonArray)
        val usage = parseResponsesUsage(response.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject)
        val status = response.get("status")?.takeIf { it.isJsonPrimitive }?.asString
        val incompleteReason = response.get("incomplete_details")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("reason")?.takeIf { it.isJsonPrimitive }?.asString
        return AIResponse(
            content = parsed.text,
            toolCalls = parsed.toolCalls,
            stopReason = responsesStopReason(status, incompleteReason, parsed.toolCalls.isNotEmpty()),
            reasoning = parsed.reasoning.takeIf { it.isNotEmpty() },
            thinkingBlocksJson = parsed.thinkingBlocksJson,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cachedInputTokens = usage.cachedInputTokens,
            images = parsed.images
        )
    }

    override fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): Flow<AIStreamChunk> = flow {
        if (useResponseApi) {
            streamViaResponses(systemPrompt, messages, tools, reasoningEffort)
            return@flow
        }

        val openAIMessages = buildList {
            if (systemPrompt.isNotBlank()) {
                add(OpenAIChatMessage(role = systemRoleForModel(), content = systemPrompt))
            }
            addAll(convertToOpenAIMessages(messages))
        }
        val toolDefs = tools.takeIf { it.isNotEmpty() }?.map { tool ->
            OpenAIToolDefinition(
                function = OpenAIFunctionDefinition(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.toJsonSchema()
                )
            )
        }

        val url = resolveApiUrl()

        val request = ChatCompletionRequest(
            model = model,
            messages = openAIMessages,
            temperature = temperature,
            reasoning_effort = normalizeReasoningEffort(reasoningEffort),
            tools = toolDefs,
            tool_choice = if (toolDefs != null) "auto" else null,
            stream = true,
            stream_options = StreamOptions(include_usage = true),
            prompt_cache_key = if (chatCacheKeyEnabled) logSessionId else null
        )
        val seq = AILogger.logRequest(logSessionId, "OpenAI", model, "POST", url, request)
        // Raw SSE is flushed to disk in batches (see AILogger.beginRawSse); finalized in finally at end of turn.
        val sseLog = AILogger.beginRawSse(logSessionId, "OpenAI", seq)

        // The streaming request is retryable as a whole; before each retry the upper layer receives a Retrying event and clears the displayed text.
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
            // tool_call index -> tool call being accumulated (order-preserving).
            val toolAccs = LinkedHashMap<Int, OpenAIToolAcc>()
            // Image-generation extension: some compatible services return images whole in delta.images (image_url data URLs).
            val streamedImages = mutableListOf<AgentImage>()
            var finishReason: String? = null
            // Stream hit end-of-stream before [DONE] arrived (peer EOF / proxy dropped early / network switch);
            // if some text exists, deliver it degraded instead of failing the whole turn; see [logStreamTruncated].
            var truncatedByNetwork = false
            var sseLines = 0
            var streamInputTokens = 0
            var streamOutputTokens = 0
            var streamCachedInputTokens = 0
            var streamCacheCreationTokens = 0

            val body = firstContent.awaitBody(api.streamChatCompletion(
                url = url,
                authorization = "Bearer $apiKey",
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
                    // Breaking on the server's [DONE] marker ends the stream normally; readLine() returning null means the marker
                    // had not arrived and the connection died (network drop / TCP reset / proxy closed early).
                    // Throw and let retry/logging take over only when no body has arrived yet; once text has streamed, deliver degraded
                    // (mark the output truncated and let the Agent loop auto-continue), avoiding a failed turn that wastes everything already streamed.
                    while (true) {
                        coroutineContext.ensureActive()
                        val line = reader.readLine()
                        if (line == null) {
                            if (!textBuilder.isSalvageableAfterTruncation()) {
                                throw IOException("SSE stream interrupted: no [DONE] terminator received (possible network drop)")
                            }
                            truncatedByNetwork = true
                            logStreamTruncated("OpenAI", textBuilder.length, sseLines)
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
                        obj.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.let { errObj ->
                            val code = errObj.get("code")?.takeIf { !it.isJsonNull }?.asString
                            val msg = errObj.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "Unknown error"
                            throw StreamApiException(code, msg)
                        }
                        // Per-line SSE parsing: upstream/model field types occasionally vary (e.g. an object written as an array, a string as an object);
                        // Gson's getAsJsonObject/getAsJsonArray throw ClassCastException on type mismatch,
                        // and asString/asInt throw UnsupportedOperationException on non-primitives.
                        // One bad line must not kill the stream: parse leniently and skip only that line; accumulated text and later lines are unaffected.
                        // CancellationException must be rethrown, or the coroutine cancellation signal would be swallowed.
                        try {
                            obj.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject?.let { u ->
                                streamInputTokens = u.get("prompt_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: streamInputTokens
                                streamOutputTokens = u.get("completion_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: streamOutputTokens
                                streamCachedInputTokens = u.getAsJsonObject("prompt_tokens_details")?.get("cached_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: streamCachedInputTokens
                                streamCacheCreationTokens = u.getAsJsonObject("prompt_tokens_details")?.get("cache_write_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: streamCacheCreationTokens
                            }
                            val choice = obj.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: continue
                            val delta = choice.getAsJsonObject("delta") ?: continue

                            choice.get("finish_reason")?.takeIf { !it.isJsonNull }?.asString?.let {
                                finishReason = it
                            }

                            // Text delta
                            delta.get("content")?.takeIf { !it.isJsonNull }?.asString?.let { c ->
                                if (c.isNotEmpty()) {
                                    budget.add(c)
                                    textBuilder.append(c)
                                    firstContent.receivedContent()
                                    onContent()
                                    emit(AIStreamChunk.TextDelta(c))
                                }
                            }
                            // Reasoning delta: standard OpenAI uses reasoning_content; some third-party compatible services
                            // (e.g. mimo) use top-level reasoning or reasoning_details (an array of reasoning.text).
                            // Live UI display only, never added to the body (reasoning is not persisted, so a retry can simply re-stream it with no duplicate-text risk),
                            // but receiving it proves the connection is live and cancels the first-byte timeout.
                            val reasoningText = delta.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString
                                ?: delta.get("reasoning")?.takeIf { !it.isJsonNull }?.asString
                                ?: delta.get("reasoning_details")?.takeIf { it.isJsonArray }?.asJsonArray
                                    ?.joinToString("") { el ->
                                        el.asJsonObject.get("text")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                                    }
                            if (!reasoningText.isNullOrEmpty()) {
                                budget.add(reasoningText)
                                firstContent.receivedContent()
                                onContent()
                                emit(AIStreamChunk.ReasoningDelta(reasoningText))
                            }
                            // Tool-call deltas: aggregate id/name/argument fragments by index.
                            // Some models (e.g. DeepSeek) send only argument fragments in later delta chunks,
                            // with id and name as empty strings ""; those must not overwrite valid values already received; otherwise the full
                            // id/name from the first chunk gets blanked by later empty values and the ToolCall is lost.
                            delta.getAsJsonArray("tool_calls")?.takeIf { it.size() > 0 }?.let {
                                firstContent.receivedContent()
                                onContent()
                            }
                            delta.getAsJsonArray("tool_calls")?.forEach { el ->
                                val tc = el.asJsonObject
                                val idx = tc.get("index")?.asInt ?: 0
                                val acc = toolAccs.getOrPut(idx) { OpenAIToolAcc() }
                                // Update only when id/name is non-empty, so empty values in delta chunks cannot clobber the first chunk's valid values
                                tc.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }?.let { acc.id = it }
                                tc.getAsJsonObject("function")?.let { fn ->
                                    fn.get("name")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }?.let { name ->
                                        // Tool name received for the first time (later deltas only carry argument fragments): notify the UI
                                        // what the model is about to call, instead of sitting on a generic "thinking" state while long arguments stream
                                        if (acc.name.isEmpty()) emit(AIStreamChunk.ToolCallDeclared(name))
                                        acc.name = name
                                    }
                                    fn.get("arguments")?.takeIf { !it.isJsonNull }?.asString?.let { budget.add(it); acc.args.append(it) }
                                }
                            }
                            // Image-generation extension: images arrive whole with no deltas and are accumulated until Final, when the workflow persists them.
                            delta.getAsJsonArray("images")?.forEach { el ->
                                val img = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                                img.toOpenAIAgentImage()?.let { image ->
                                    streamedImages.add(image)
                                    firstContent.receivedContent()
                                    onContent()
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: StreamApiException) {
                            throw e
                        } catch (e: Exception) {
                            coroutineContext.ensureActive()
                            // This SSE line failed to parse; skip it without affecting accumulated text or later lines.
                        }
                    }
                } finally {
                    idleWatchdog.cancel()
                    closeHandle?.dispose()
                }
            }

            val toolCalls = toolAccs.values
                .filter { it.id.isNotEmpty() || it.name.isNotEmpty() }
                // On a broken stream the arguments are almost certainly truncated: running a tool (write file / run command) with mangled arguments is far riskier than dropping the call.
                .filter { !truncatedByNetwork || looksLikeCompleteJsonObject(it.args.toString()) }
                .map { acc -> ToolCall(id = acc.id, name = acc.name, arguments = parseToolArguments(acc.args.toString())) }
            emit(AIStreamChunk.Final(AIResponse(content = textBuilder.toString(), toolCalls = toolCalls, stopReason = if (truncatedByNetwork) STREAM_TRUNCATED_STOP_REASON else finishReason, inputTokens = streamInputTokens, outputTokens = streamOutputTokens, cachedInputTokens = streamCachedInputTokens, cacheCreationTokens = streamCacheCreationTokens, images = streamedImages)))
                    }
            },
            onRetry = { attempt, max, error -> emit(AIStreamChunk.Retrying(attempt, max, error)) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "OpenAI", enriched, seq)
            throw enriched
        } finally {
            // On success, failure, or cancellation, flush the received raw SSE to disk (retries resume from where the last attempt stopped).
            sseLog.finish()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Streaming Responses API request. Unlike Chat Completions' delta frames, this is a semantic event stream (see
     * [ResponsesStreamAccumulator]), and **the official API sends no `[DONE]`**: completion can only be judged by
     * response.completed / incomplete / failed. Keeping the old "no [DONE] means a broken stream" rule would treat
     * normal completion as an error, so [ResponsesStreamAccumulator.terminated] is the exit condition here; only an unterminated read to end-of-stream counts as truncated.
     */
    private suspend fun FlowCollector<AIStreamChunk>.streamViaResponses(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ) {
        val url = resolveApiUrl()
        val request = buildResponsesRequest(systemPrompt, messages, tools, reasoningEffort, stream = true)
        val seq = AILogger.logRequest(logSessionId, "OpenAI", model, "POST", url, request)
        // Raw SSE is flushed to disk in batches (see AILogger.beginRawSse); finalized in finally at end of turn.
        val sseLog = AILogger.beginRawSse(logSessionId, "OpenAI", seq)
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
                    val acc = ResponsesStreamAccumulator()

                    val body = firstContent.awaitBody(api.streamResponses(
                        url = url,
                        authorization = "Bearer $apiKey",
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
                            while (!acc.terminated) {
                                coroutineContext.ensureActive()
                                val line = reader.readLine()
                                if (line == null) {
                                    // The connection died before a terminal event. Throw and let retry/logging take over only when no body has arrived;
                                    // once text has streamed, deliver degraded (mark the output truncated and let the Agent loop auto-continue).
                                    if (!acc.hasSalvageableText()) {
                                        throw IOException("SSE stream interrupted: no response.completed event received (possible network drop)")
                                    }
                                    logStreamTruncated("OpenAI/Responses", acc.textLength, sseLines)
                                    acc.markNetworkTruncated()
                                    break
                                }
                                sseLines++
                                idleWatchdog.touch()
                                if (!line.startsWith("data:")) continue
                                val data = line.removePrefix("data:").trim()
                                if (data.isEmpty()) continue
                                sseLog.append(line)
                                // Official Responses sends no [DONE], but some compatible services append one; receiving it ends the stream.
                                if (data == "[DONE]") break
                                val obj = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                                obj.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.let { errObj ->
                                    val code = errObj.get("code")?.takeIf { !it.isJsonNull }?.asString
                                    val msg = errObj.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "Unknown error"
                                    throw StreamApiException(code, msg)
                                }
                                // A field-type error in one event must not kill the stream; skip only that event,
                                // but StreamApiException (response.failed) and cancellation signals must be rethrown.
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
                                    is ResponsesDelta.Text -> {
                                        firstContent.receivedContent()
                                        onContent()
                                        emit(AIStreamChunk.TextDelta(delta.text))
                                    }
                                    // Reasoning deltas are UI display only and never added to the body; receiving one proves the connection is live and cancels the first-byte timeout.
                                    is ResponsesDelta.Reasoning -> {
                                        firstContent.receivedContent()
                                        onContent()
                                        emit(AIStreamChunk.ReasoningDelta(delta.text))
                                    }
                                    // The tool name arrives before the arguments: notify the UI to switch to a concrete scene early
                                    is ResponsesDelta.ToolCallDeclared -> {
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
            AILogger.logError(logSessionId, "OpenAI", enriched, seq)
            throw enriched
        } finally {
            sseLog.finish()
        }
    }

    /** Tool-call state accumulated by index during streaming. */
    private class OpenAIToolAcc {
        var id = ""
        var name = ""
        val args = StringBuilder()
    }

    private fun convertToOpenAIMessages(messages: List<AgentMessage>): MutableList<OpenAIChatMessage> {
        val raw = messages.map { message ->
            when (message) {
                is AgentMessage.UserMessage -> OpenAIChatMessage(
                    role = "user",
                    content = message.toOpenAIUserContent()
                )
                is AgentMessage.AssistantMessage -> {
                    val toolCalls = if (message.toolCalls.isNotEmpty()) {
                        message.toolCalls.map { convertToOpenAIToolCall(it) }
                    } else null
                    // DeepSeek thinking mode requires the assistant message's reasoning_content field to exist
                    // (even as an empty string), or the API returns 400 when tool-call turns are replayed.
                    val reasoningContent = if (model.contains("deepseek", ignoreCase = true)) {
                        message.reasoning
                    } else {
                        message.reasoning.ifEmpty { null }
                    }
                    OpenAIChatMessage(
                        role = "assistant",
                        content = message.content,
                        tool_calls = toolCalls,
                        reasoning_content = reasoningContent
                    )
                }
                is AgentMessage.ToolResultMessage -> {
                    // File-type tools feed the model a condensed projection (fewer tokens than replaying the full diff); the UI/persistence still use the full result.
                    val modelText = message.modelResult
                        ?: modelToolResultText(message.toolName, message.result)
                        ?: message.result
                    val content: Any = if (message.images.isNotEmpty()) {
                        val parts = mutableListOf<Map<String, Any>>()
                        if (modelText.isNotBlank()) {
                            parts.add(mapOf("type" to "text", "text" to modelText))
                        }
                        message.images.forEach { image ->
                            parts.add(image.toOpenAIImagePart())
                        }
                        parts
                    } else {
                        modelText
                    }
                    OpenAIChatMessage(
                        role = "tool",
                        content = content,
                        tool_call_id = message.id
                    )
                }
            }
        }

        // Defensive cleanup: ensure each assistant(tool_calls) and its tool response messages are paired one-to-one by tool_call_id and adjacent,
        // avoiding upstream 400s. Scenarios that can break pairing:
        // 1) Concurrent/async tool results landing out of order (e.g. other tool results jumping ahead while askUserQuestion blocks):
        //    assistant(tool_calls) and its responses separated by other messages -> absorb the matching tool responses back right after it;
        // 2) Orphaned tool messages (the preceding assistant has no tool_calls, e.g. pairing broken by context compression) -> skip,
        //    otherwise OpenAI errors with "Messages with role 'tool' must be a response to a preceding
    //    message with 'tool_calls'";
        // 3) Assistant-declared tool_calls with no matching response (e.g. some calls not executed because the user declined) -> trim,
        //    otherwise OpenAI errors with "insufficient tool messages following tool_calls message".
        val cleaned = mutableListOf<OpenAIChatMessage>()
        val consumed = BooleanArray(raw.size)
        for (i in raw.indices) {
            if (consumed[i]) continue
            val msg = raw[i]
            if (msg.role == "assistant" && msg.tool_calls?.isNotEmpty() == true) {
                val remaining = msg.tool_calls!!.map { it.id }.toMutableSet()
                val matchedTools = mutableListOf<OpenAIChatMessage>()
                for (j in i + 1 until raw.size) {
                    if (consumed[j]) continue
                    val m = raw[j]
                    if (m.role == "tool" && m.tool_call_id != null && m.tool_call_id in remaining) {
                        matchedTools.add(m)
                        consumed[j] = true
                        remaining.remove(m.tool_call_id)
                        if (remaining.isEmpty()) break
                    }
                }
                val keptCalls = if (remaining.isEmpty()) msg.tool_calls
                else msg.tool_calls!!.filter { it.id !in remaining }
                cleaned.add(if (keptCalls === msg.tool_calls) msg else msg.copy(tool_calls = keptCalls.ifEmpty { null }))
                cleaned.addAll(matchedTools)
            } else if (msg.role == "tool") {
                consumed[i] = true // Orphaned tool message, skip
            } else {
                cleaned.add(msg)
            }
        }
        return cleaned
    }

    private fun AgentMessage.UserMessage.toOpenAIUserContent(): Any {
        if (images.isEmpty()) return content

        val parts = mutableListOf<Map<String, Any>>()
        if (content.isNotBlank()) {
            parts.add(mapOf("type" to "text", "text" to content))
        }
        images.forEach { image ->
            parts.add(image.toOpenAIImagePart())
        }
        return parts
    }

    private fun AgentImage.toOpenAIImagePart(): Map<String, Any> = mapOf(
        "type" to "image_url",
        "image_url" to mapOf(
            "url" to "data:$mimeType;base64,$base64Data",
            "detail" to "auto"
        )
    )

    /** `data:` URL (base64-embedded) -> AgentImage; remote URLs / non-base64 / invalid all return null. */
    private fun dataUrlToAgentImage(url: String): AgentImage? {
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val header = url.substring(5, comma)
        if (!header.contains(";base64", ignoreCase = true)) return null
        val mime = header.substringBefore(';').ifBlank { "image/png" }
        val data = url.substring(comma + 1)
        if (data.isBlank()) return null
        return AgentImage(mimeType = mime, base64Data = data)
    }

    /** Single element of streaming delta.images -> AgentImage. */
    private fun com.google.gson.JsonObject.toOpenAIAgentImage(): AgentImage? {
        val url = when (val img = get("image_url")) {
            is com.google.gson.JsonPrimitive -> img.asString
            is com.google.gson.JsonObject -> img.get("url")?.takeIf { it.isJsonPrimitive }?.asString
            else -> null
        } ?: return null
        return dataUrlToAgentImage(url)
    }

    /** Single image item of non-streaming message.images -> AgentImage. */
    private fun OpenAIImagePart.toAgentImage(): AgentImage? {
        val url = image_url?.url ?: return null
        return dataUrlToAgentImage(url)
    }

    /**
     * The content returned by OpenAI chat completion can be a string or an array (multimodal/image-generation models).
     * Array elements may contain image_url base64 data URLs (several MB); calling toString() directly would persist the
     * whole base64 as assistant text, blow up the SQLite CursorWindow, and crash startup. Only the text parts are extracted;
     * images keep only a caption/remote URL reference, and base64 is never written into content.
     */
    private fun Any?.asTextContent(): String = when (this) {
        null -> ""
        is String -> this
        is List<*> -> extractTextFromContentParts(this)
        else -> toString()
    }

    private fun extractTextFromContentParts(parts: List<*>): String {
        val text = StringBuilder()
        for (part in parts) {
            when (part) {
                is Map<*, *> -> {
                    when (val type = part["type"] as? String) {
                        "text", "input_text", "output_text" -> {
                            (part["text"] as? String)?.let { text.append(it) }
                        }
                        "image_url" -> {
                            val url = when (val img = part["image_url"]) {
                                is String -> img
                                is Map<*, *> -> img["url"] as? String
                                else -> null
                            }
                            if (url != null && url.startsWith("data:image", ignoreCase = true)) {
                                text.append("\n[Image omitted: inline image data too large]")
                            } else if (!url.isNullOrBlank()) {
                                text.append("\n[Image: ").append(url).append("]")
                            }
                        }
                        "input_image" -> {
                            text.append("\n[Image omitted: inline image data too large]")
                        }
                        else -> {}
                    }
                }
                is String -> text.append(part)
                else -> {}
            }
        }
        return text.toString()
    }

    private fun convertToToolCall(openAIToolCall: OpenAIToolCall): ToolCall {
        val argumentsJson = runCatching {
            Json.parseToJsonElement(openAIToolCall.function.arguments).jsonObject
        }.getOrElse { JsonObject(emptyMap()) }
        return ToolCall(
            id = openAIToolCall.id,
            name = openAIToolCall.function.name,
            arguments = argumentsJson
        )
    }

    private fun convertToOpenAIToolCall(toolCall: ToolCall): OpenAIToolCall {
        return OpenAIToolCall(
            id = toolCall.id,
            type = "function",
            function = com.xencode.feature.agent.data.remote.openai.OpenAIFunctionCall(
                name = toolCall.name,
                arguments = JsonObject(toolCall.arguments).toString()
            )
        )
    }
}
