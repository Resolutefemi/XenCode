package com.xencode.feature.agent.domain.provider

import com.xencode.feature.agent.data.remote.anthropic.AnthropicApi
import com.xencode.feature.agent.data.remote.anthropic.AnthropicMessageRequest
import com.xencode.feature.agent.data.remote.anthropic.AnthropicMessage
import com.xencode.feature.agent.data.remote.anthropic.AnthropicContentBlock
import com.xencode.feature.agent.data.remote.anthropic.AnthropicThinkingConfig
import com.xencode.feature.agent.data.remote.anthropic.AnthropicOutputConfig
import com.xencode.feature.agent.data.remote.anthropic.AnthropicToolDefinition
import com.xencode.core.util.AILogger
import com.xencode.feature.agent.domain.model.AgentImage
import com.xencode.feature.agent.domain.model.AgentMessage
import com.xencode.feature.agent.domain.tool.AgentTool
import com.xencode.feature.agent.domain.tool.ToolCall
import com.xencode.feature.agent.domain.tool.modelToolResultText
import com.xencode.feature.settings.domain.model.ProviderType
import com.xencode.feature.settings.domain.model.defaultProviderApiPath
import com.google.gson.JsonParser
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

class AnthropicAdapter @Inject constructor(
    private val api: AnthropicApi
) : AIProvider {

    override var apiKey = ""
    override var keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? = null
    override var baseUrl = "https://api.anthropic.com/"
    override var useFullUrl = false
    override var useResponseApi = false
    override var model = "claude-3-5-sonnet-20241022"
    override var providerId = ""
    override var logSessionId: String? = null
    override var firstByteTimeoutMs: Long = FIRST_BYTE_TIMEOUT_MS
    override var streamIdleTimeoutMs: Long = 0L
    override var maxNetworkRetries: Int = MAX_NETWORK_RETRIES

    /** Whether explicit cache breakpoints (cache_control) are enabled. On by default; a settings toggle turns them off for third-party compatible gateways that strictly validate unknown fields. */
    var cacheBreakpointsEnabled: Boolean = true

    /** Custom request headers: written out after placeholder substitution, fully overriding same-named default headers. */
    override var customHeaders: Map<String, String> = emptyMap()

    override var maxOutputTokens: Int? = null

    // When null, the request body omits temperature (Gson skips null fields) and the server default applies; with thinking enabled the API requires omitting it.
    override var temperature: Float? = null

    private fun extraHeaders(): Map<String, String> =
        resolveCustomHeaders(customHeaders, logSessionId, apiKey)

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        val anthropicMessages = convertToAnthropicMessages(messages, cacheBreakpointsEnabled)

        val toolDefs = tools.takeIf { it.isNotEmpty() }?.mapIndexed { index, tool ->
            AnthropicToolDefinition(
                name = tool.name,
                description = tool.description,
                input_schema = tool.toJsonSchema(),
                // Breakpoint budget is limited (at most 4 per request), so tools tag only the last one.
                cache_control = if (cacheBreakpointsEnabled && index == tools.lastIndex) CACHE_BREAKPOINT else null
            )
        }

        val url = if (useFullUrl) baseUrl else joinUrl(baseUrl, defaultProviderApiPath(ProviderType.ANTHROPIC))
        val (thinking, outputConfig) = buildThinkingConfig(reasoningEffort)
        val request = AnthropicMessageRequest(
            model = model,
            messages = anthropicMessages,
            system = buildSystemPayload(systemPrompt),
            max_tokens = resolveMaxTokens(thinking),
            temperature = if (thinking != null) null else temperature,
            thinking = thinking,
            output_config = outputConfig,
            tools = toolDefs,
            stream = false
        )
        val seq = AILogger.logRequest(logSessionId, "Anthropic", model, "POST", url, request)

        val triedKeys = mutableSetOf(apiKey)
        val response = try {
            retryStaircase(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, _ -> switchKeyOnFailure(e, triedKeys) != null }
            ) {
                api.createMessage(url = url, apiKey = apiKey, extraHeaders = extraHeaders(), request = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Anthropic", enriched, seq)
            throw enriched
        }
        AILogger.logResponse(logSessionId, "Anthropic", response, seq)

        var contentText = ""
        var thinkingText = ""
        var signature: String? = null
        val toolCalls = mutableListOf<ToolCall>()
        // thinking / redacted_thinking blocks are kept verbatim in returned order; when replayed they must not be modified or reordered.
        val thinkingBlocks = mutableListOf<AnthropicContentBlock>()

        for (block in response.content) {
            when (block.type) {
                "text" -> contentText += block.text ?: ""
                "thinking" -> {
                    thinkingText += block.thinking ?: ""
                    signature = block.signature ?: signature
                    thinkingBlocks.add(block)
                }
                "redacted_thinking" -> thinkingBlocks.add(block)
                "tool_use" -> {
                    val arguments = block.input?.let { mapToJson(it) } ?: JsonObject(emptyMap())
                    toolCalls.add(
                        ToolCall(
                            id = block.id ?: "",
                            name = block.name ?: "",
                            arguments = arguments
                        )
                    )
                }
            }
        }

        return AIResponse(content = contentText, toolCalls = toolCalls, stopReason = response.stop_reason, stopDetail = response.stop_details?.explanation, reasoning = thinkingText.ifEmpty { null }, signature = signature, thinkingBlocksJson = encodeThinkingBlocks(thinkingBlocks), inputTokens = totalInputTokens(response.usage.input_tokens, response.usage.cache_read_input_tokens, response.usage.cache_creation_input_tokens), outputTokens = response.usage.output_tokens, cachedInputTokens = response.usage.cache_read_input_tokens ?: 0, cacheCreationTokens = response.usage.cache_creation_input_tokens ?: 0)
    }

    override fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): Flow<AIStreamChunk> = flow {
        val anthropicMessages = convertToAnthropicMessages(messages, cacheBreakpointsEnabled)
        val toolDefs = tools.takeIf { it.isNotEmpty() }?.mapIndexed { index, tool ->
            AnthropicToolDefinition(
                name = tool.name,
                description = tool.description,
                input_schema = tool.toJsonSchema(),
                cache_control = if (cacheBreakpointsEnabled && index == tools.lastIndex) CACHE_BREAKPOINT else null
            )
        }

        val url = if (useFullUrl) baseUrl else joinUrl(baseUrl, defaultProviderApiPath(ProviderType.ANTHROPIC))
        val (thinking, outputConfig) = buildThinkingConfig(reasoningEffort)
        val request = AnthropicMessageRequest(
            model = model,
            messages = anthropicMessages,
            system = buildSystemPayload(systemPrompt),
            max_tokens = resolveMaxTokens(thinking),
            temperature = if (thinking != null) null else temperature,
            thinking = thinking,
            output_config = outputConfig,
            tools = toolDefs,
            stream = true
        )
        val seq = AILogger.logRequest(logSessionId, "Anthropic", model, "POST", url, request)
        // Raw SSE is flushed to disk in batches (see AILogger.beginRawSse); finalized in finally at end of turn.
        val sseLog = AILogger.beginRawSse(logSessionId, "Anthropic", seq)

        // The streaming request is retryable as a whole; before each retry the upper layer receives a Retrying event and clears the displayed text.
        val triedKeys = mutableSetOf(apiKey)
        try {
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
            // content block index -> tool_use being accumulated (entries only for tool_use blocks, order-preserving).
            val toolBlocks = LinkedHashMap<Int, ToolBlockAcc>()
            var stopReason: String? = null
            var stopDetail: String? = null
            // Stream hit end-of-stream before message_stop arrived (peer EOF / proxy dropped early / network switch).
            // If some text exists, deliver it degraded instead of failing the whole turn; see [logStreamTruncated].
            var truncatedByNetwork = false
            var sseLines = 0
            var streamInputTokens = 0
            var streamOutputTokens = 0
            var streamCachedInputTokens = 0
            var streamCacheCreationTokens = 0
            // Encrypted signature of the thinking block (carried by signature_delta events); surfaced with Final for the tool loop to replay.
            var signature: String? = null
            // content block index -> accumulated thinking / redacted_thinking; slotted by index so several thinking blocks in one turn are not merged.
            val thinkingBlocks = LinkedHashMap<Int, ThinkingBlockAcc>()

            val body = firstContent.awaitBody(api.streamMessage(url = url, apiKey = apiKey, extraHeaders = extraHeaders(), request = request))

            body.use { rb ->
                firstContent.attach { rb.close() }
                val idleWatchdog = launchStreamIdleWatchdog(streamIdleTimeoutMs) { firstContent.closeBody() }
                val closeHandle = coroutineContext[Job]?.invokeOnCompletion {
                    runCatching { rb.close() }
                }
                try {
                    val reader = rb.charStream().buffered()
                    // Breaking on the server's message_stop event ends the stream normally; readLine() returning null means
                    // the connection died before the terminator arrived (network drop / TCP reset / proxy closed early).
                    // Throw and let retry/logging take over only when no body has arrived yet; resending is then safe and may yield a full answer.
                    // Once text has streamed, deliver it degraded (mark the output truncated and let the Agent loop auto-continue),
                    // otherwise the user sees the whole turn fail and everything streamed so far is wasted.
                    while (true) {
                        coroutineContext.ensureActive()
                        val line = reader.readLine()
                        if (line == null) {
                            if (!textBuilder.isSalvageableAfterTruncation()) {
                                throw IOException("SSE stream interrupted: no message_stop terminator received (possible network drop)")
                            }
                            truncatedByNetwork = true
                            logStreamTruncated("Anthropic", textBuilder.length, sseLines)
                            break
                        }
                        sseLines++
                        idleWatchdog.touch()
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        sseLog.append(line)
                        val obj = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                        // Per-line SSE parsing: upstream/model field types occasionally vary; Gson's getAsJsonObject/getAsJsonArray
                        // throw ClassCastException on type mismatch, and asString/asInt throw UnsupportedOperationException on non-primitives.
                        // One bad line must not kill the stream: parse leniently and skip only that line; CancellationException must be rethrown.
                        try {
                            when (obj.get("type")?.asString) {
                                "error" -> {
                                    val errObj = obj.getAsJsonObject("error")
                                    val code = errObj?.get("type")?.takeIf { !it.isJsonNull }?.asString
                                    val msg = errObj?.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "Unknown error"
                                    throw StreamApiException(code, msg)
                                }
                                "message_start" -> {
                                    val usage = obj.get("message")?.takeIf { it.isJsonObject }?.asJsonObject
                                        ?.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
                                    streamInputTokens = usage?.get("input_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                    // Cache hit/write counts come back in message_start's usage; some upstreams complete them in message_delta (see the override logic in that branch).
                                    streamCachedInputTokens = usage?.get("cache_read_input_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                    streamCacheCreationTokens = usage?.get("cache_creation_input_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                }
                                "content_block_start" -> {
                                    val index = obj.get("index")?.asInt ?: continue
                                    val block = obj.getAsJsonObject("content_block")
                                    when (block?.get("type")?.asString) {
                                        "tool_use" -> {
                                            firstContent.receivedContent()
                                            onContent()
                                            val name = block.get("name")?.asString ?: ""
                                            toolBlocks[index] = ToolBlockAcc(
                                                id = block.get("id")?.asString ?: "",
                                                name = name
                                            )
                                            // The tool name arrives before the arguments: tell the UI right away what the model is about to call,
                                            // instead of sitting on a generic "thinking" state while the arguments stream.
                                            if (name.isNotEmpty()) emit(AIStreamChunk.ToolCallDeclared(name))
                                        }
                                        "thinking" -> thinkingBlocks[index] = ThinkingBlockAcc(type = "thinking").also { acc ->
                                            val initial = block.get("thinking")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                            budget.add(initial)
                                            acc.thinking.append(initial)
                                            if (initial.isNotEmpty()) {
                                                firstContent.receivedContent()
                                                onContent()
                                            }
                                            acc.signature = block.get("signature")?.takeIf { !it.isJsonNull }?.asString
                                        }
                                        // redacted_thinking data arrives whole in the start event; there is no matching delta.
                                        "redacted_thinking" -> thinkingBlocks[index] = ThinkingBlockAcc(type = "redacted_thinking").also { acc ->
                                            acc.data = block.get("data")?.takeIf { !it.isJsonNull }?.asString
                                            if (!acc.data.isNullOrEmpty()) {
                                                firstContent.receivedContent()
                                                onContent()
                                            }
                                        }
                                    }
                                }
                                "content_block_delta" -> {
                                    val delta = obj.getAsJsonObject("delta") ?: continue
                                    when (delta.get("type")?.asString) {
                                        "text_delta" -> {
                                            val t = delta.get("text")?.asString ?: ""
                                            if (t.isNotEmpty()) {
                                                budget.add(t)
                                                textBuilder.append(t)
                                                firstContent.receivedContent()
                                                onContent()
                                                emit(AIStreamChunk.TextDelta(t))
                                            }
                                        }
                                        "thinking_delta" -> {
                                            val t = delta.get("thinking")?.asString ?: ""
                                            if (t.isNotEmpty()) {
                                                budget.add(t)
                                                obj.get("index")?.asInt?.let { idx ->
                                                    thinkingBlocks.getOrPut(idx) { ThinkingBlockAcc(type = "thinking") }
                                                        .thinking.append(t)
                                                }
                                                // Reasoning is not persisted and can re-stream on retry, but receiving it proves the connection is live; cancel the first-byte timeout.
                                                firstContent.receivedContent()
                                                onContent()
                                                emit(AIStreamChunk.ReasoningDelta(t))
                                            }
                                        }
                                        "signature_delta" -> {
                                            val sig = delta.get("signature")?.asString ?: ""
                                            if (sig.isNotEmpty()) {
                                                signature = sig
                                                obj.get("index")?.asInt?.let { idx ->
                                                    thinkingBlocks.getOrPut(idx) { ThinkingBlockAcc(type = "thinking") }
                                                        .signature = sig
                                                }
                                            }
                                        }
                                        "input_json_delta" -> {
                                            val index = obj.get("index")?.asInt
                                            val partial = delta.get("partial_json")?.asString ?: ""
                                            budget.add(partial)
                                            if (index != null) toolBlocks[index]?.args?.append(partial)
                                        }
                                    }
                                }
                                "message_stop" -> break
                                "message_delta" -> {
                                    val delta = obj.get("delta")?.takeIf { it.isJsonObject }?.asJsonObject
                                    delta?.get("stop_reason")?.takeIf { !it.isJsonNull }?.asString?.let {
                                        stopReason = it
                                    }
                                    delta?.get("stop_details")?.takeIf { it.isJsonObject }?.asJsonObject
                                        ?.get("explanation")?.takeIf { !it.isJsonNull }?.asString?.let {
                                            stopDetail = it
                                        }
                                    val usage = obj.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
                                    // The official message_delta usage carries only output_tokens, but some upstreams (e.g. New API relays)
                                    // include the full usage here, and this input_tokens is the final value, possibly different from message_start,
                                    // so override when present to avoid undercounting the uncached share of total input.
                                    usage?.get("input_tokens")?.takeIf { !it.isJsonNull }?.asInt?.let {
                                        streamInputTokens = it
                                    }
                                    usage?.get("output_tokens")?.takeIf { !it.isJsonNull }?.asInt?.let {
                                        streamOutputTokens = it
                                    }
                                    usage?.get("cache_read_input_tokens")?.takeIf { !it.isJsonNull }?.asInt?.let {
                                        streamCachedInputTokens = it
                                    }
                                    usage?.get("cache_creation_input_tokens")?.takeIf { !it.isJsonNull }?.asInt?.let {
                                        streamCacheCreationTokens = it
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
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

            val toolCalls = toolBlocks.values
                // On a broken stream the arguments are almost certainly truncated; running a tool (write file / run command) with mangled arguments is far riskier than dropping the call.
                .filter { !truncatedByNetwork || looksLikeCompleteJsonObject(it.args.toString()) }
                .map { acc ->
                    ToolCall(id = acc.id, name = acc.name, arguments = parseArgs(acc.args.toString()))
                }
            emit(AIStreamChunk.Final(AIResponse(content = textBuilder.toString(), toolCalls = toolCalls, stopReason = if (truncatedByNetwork) STREAM_TRUNCATED_STOP_REASON else stopReason, stopDetail = stopDetail, signature = signature, thinkingBlocksJson = encodeThinkingBlocks(thinkingBlocks.values.map { it.toBlock() }), inputTokens = totalInputTokens(streamInputTokens, streamCachedInputTokens, streamCacheCreationTokens), outputTokens = streamOutputTokens, cachedInputTokens = streamCachedInputTokens, cacheCreationTokens = streamCacheCreationTokens)))
                    }
                },
                onRetry = { attempt, max, error -> emit(AIStreamChunk.Retrying(attempt, max, error)) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Anthropic", enriched, seq)
            throw enriched
        } finally {
            // On success, failure, or cancellation, flush the received raw SSE to disk (retries resume from where the last attempt stopped).
            sseLog.finish()
        }
    }.flowOn(Dispatchers.IO)

    /** tool_use state accumulated by content block index during streaming. */
    private class ToolBlockAcc(val id: String, val name: String) {
        val args = StringBuilder()
    }

    /** thinking / redacted_thinking state accumulated by content block index during streaming. */
    private class ThinkingBlockAcc(val type: String) {
        val thinking = StringBuilder()
        var signature: String? = null
        var data: String? = null

        fun toBlock(): AnthropicContentBlock = AnthropicContentBlock(
            type = type,
            thinking = if (type == "thinking") thinking.toString() else null,
            signature = signature,
            data = data
        )
    }

    /**
     * Max output tokens: prefer the model metadata output limit, falling back to [DEFAULT_MAX_TOKENS] when absent.
     * With thinking on, the thinking budget counts toward max_tokens and room must be left for the body, so it must be at least budget + [MIN_CONTENT_TOKENS].
     */
    private fun resolveMaxTokens(thinking: AnthropicThinkingConfig?): Int {
        val limit = maxOutputTokens?.takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS
        val budget = thinking?.budget_tokens ?: return limit
        return maxOf(limit, budget + MIN_CONTENT_TOKENS)
    }

    /** Serialize thinking / redacted_thinking blocks verbatim as a snapshot; null when there are none. */
    private fun encodeThinkingBlocks(blocks: List<AnthropicContentBlock>): String? =
        blocks.takeIf { it.isNotEmpty() }?.let { gson.toJson(it) }

    /** Deserialize a snapshot from [encodeThinkingBlocks]; returns an empty list when corrupt or empty, and the caller falls back to the legacy path. */
    private fun decodeThinkingBlocks(json: String): List<AnthropicContentBlock> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            gson.fromJson(json, Array<AnthropicContentBlock>::class.java)?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * Reasoning effort -> Anthropic thinking config.
     * - Newer models (4.6+/5 series, adaptive-capable): the effort level passes straight into output_config and thinking uses adaptive+summarized;
     *   "none" disables thinking; "minimal" has no matching level and normalizes to low (officially only low/medium/high/xhigh/max).
     * - Older models (4.5 and earlier, budget_tokens only): low/medium/high map to 1024/4096/8192 (must stay below max_tokens);
     *   minimal normalizes to low, xhigh/max to high.
     */
    private fun buildThinkingConfig(reasoningEffort: String?): Pair<AnthropicThinkingConfig?, AnthropicOutputConfig?> {
        if (reasoningEffort == null) return null to null
        if (supportsAdaptiveThinking()) {
            if (reasoningEffort == "none") {
                return AnthropicThinkingConfig(type = "disabled") to null
            }
            // The official effort scale has no minimal level; normalize to low to avoid a 400 from passing an invalid level.
            val effort = if (reasoningEffort == "minimal") "low" else reasoningEffort
            return AnthropicThinkingConfig(type = "adaptive", display = "summarized") to AnthropicOutputConfig(effort = effort)
        }
        val budget = when (reasoningEffort) {
            // Anthropic has no minimal level: normalize to the nearest one, low (avoids a split where the same level 400s on one side and is ignored on the other).
            "minimal", "low" -> 1024
            "medium" -> 4096
            "high", "xhigh", "max" -> 8192
            else -> return null to null
        }
        return AnthropicThinkingConfig(type = "enabled", budget_tokens = budget) to null
    }

    /** Whether adaptive thinking is supported: 4.6+ and 5 series (claude-<family>-4-6/4-7/4-8 or major version 5). */
    private fun supportsAdaptiveThinking(): Boolean {
        val m = Regex("claude-(?:opus|sonnet|haiku|fable|mythos)-(\\d+)(?:-(\\d+))?").find(model)
        val major = m?.groupValues?.get(1)?.toIntOrNull() ?: return false
        val minor = m?.groupValues?.get(2)?.toIntOrNull()
        return major >= 5 || (major == 4 && (minor ?: 0) >= 6)
    }

    /** Parse the accumulated tool-argument JSON string into a JsonObject; falls back to an empty object when blank or invalid. */
    private fun parseArgs(raw: String): JsonObject {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrElse { JsonObject(emptyMap()) }
    }

    private fun convertToAnthropicMessages(messages: List<AgentMessage>, addMessageBreakpoint: Boolean): MutableList<AnthropicMessage> {
        val result = mutableListOf<AnthropicMessage>()
        // Defensive tracking: whether the previous assistant message contained tool_use
        var lastAssistantHadToolUse = false
        // Index in result of the last plain user message (not a tool_result), for placing the messages breakpoint.
        var lastPlainUserIndex: Int? = null

        for (message in messages) {
            when (message) {
                is AgentMessage.UserMessage -> {
                    result.add(AnthropicMessage(role = "user", content = message.toAnthropicUserContent()))
                    lastPlainUserIndex = result.lastIndex
                    lastAssistantHadToolUse = false
                }
                is AgentMessage.AssistantMessage -> {
                    val contentBlocks = mutableListOf<AnthropicContentBlock>()
                    // Across tool loops/multi-turn, the previous turn's thinking / redacted_thinking blocks must be replayed verbatim and in order, or 400.
                    // Prefer the native snapshot (the only form that satisfies "must not modify"); fall back to a single block for old data / restored backups that have only signature.
                    val snapshot = decodeThinkingBlocks(message.thinkingBlocksJson)
                    if (snapshot.isNotEmpty()) {
                        contentBlocks.addAll(snapshot)
                    } else if (message.signature.isNotEmpty()) {
                        contentBlocks.add(
                            AnthropicContentBlock(
                                type = "thinking",
                                thinking = message.reasoning,
                                signature = message.signature
                            )
                        )
                    }
                    if (message.content.isNotEmpty()) {
                        contentBlocks.add(AnthropicContentBlock(type = "text", text = message.content))
                    }

                    for (toolCall in message.toolCalls) {
                         @Suppress("UNCHECKED_CAST")
                         val inputMap = jsonElementToMap(JsonObject(toolCall.arguments)) as Map<String, Any>

                         contentBlocks.add(
                            AnthropicContentBlock(
                                type = "tool_use",
                                id = toolCall.id,
                                name = toolCall.name,
                                input = inputMap
                            )
                        )
                    }

                    lastAssistantHadToolUse = message.toolCalls.isNotEmpty()

                    if (contentBlocks.isNotEmpty()) {
                        result.add(AnthropicMessage(role = "assistant", content = contentBlocks))
                    }
                }
                is AgentMessage.ToolResultMessage -> {
                    // Defensive cleanup: skip orphaned tool_results with no matching tool_use
                    if (!lastAssistantHadToolUse) continue
                    // File-type tools feed the model a condensed projection (fewer tokens than replaying the full diff); the UI/persistence still use the full result.
                    val modelText = message.modelResult
                        ?: modelToolResultText(message.toolName, message.result)
                        ?: message.result
                    val content: Any = if (message.images.isNotEmpty()) {
                        val contentList = mutableListOf<AnthropicContentBlock>()
                        if (modelText.isNotBlank()) {
                            contentList.add(AnthropicContentBlock(type = "text", text = modelText))
                        }
                        message.images.forEach { img ->
                            contentList.add(img.toAnthropicImageBlock())
                        }
                        contentList
                    } else {
                        modelText
                    }
                    val resultBlock = AnthropicContentBlock(
                        type = "tool_result",
                        tool_use_id = message.id,
                        content = content
                    )
                    // tool_results for multiple tool_use in one assistant message must all go into the single user message immediately following:
                    // the official endpoint merges consecutive user messages into one turn, but some compatible gateways do not and fail with
                    // "`tool_use` ids were found without `tool_result` blocks immediately after".
                    val previous = result.lastOrNull()
                    val previousBlocks = (previous?.content as? List<*>)?.filterIsInstance<AnthropicContentBlock>()
                    if (
                        previous?.role == "user" &&
                        !previousBlocks.isNullOrEmpty() &&
                        previousBlocks.all { it.type == "tool_result" }
                    ) {
                        result[result.lastIndex] = previous.copy(content = previousBlocks + resultBlock)
                    } else {
                        result.add(AnthropicMessage(role = "user", content = listOf(resultBlock)))
                    }
                }
            }
        }

        // messages breakpoint: placed on the last plain user message's text block (Anthropic does not allow it on tool_result/thinking/image blocks).
        // When the last user message in a tool loop is a tool_result, skip it; the system+tools breakpoints are enough.
        if (addMessageBreakpoint) {
            val idx = lastPlainUserIndex
            if (idx != null) {
                val msg = result[idx]
                val newContent = when (val c = msg.content) {
                    is String -> listOf(
                        AnthropicContentBlock(type = "text", text = c, cache_control = CACHE_BREAKPOINT)
                    )
                    is List<*> -> {
                        val blocks = c.map { it as AnthropicContentBlock }
                        val lastText = blocks.indexOfLast { it.type == "text" }
                        if (lastText >= 0) {
                            blocks.mapIndexed { i, b ->
                                if (i == lastText) b.copy(cache_control = CACHE_BREAKPOINT) else b
                            }
                        } else {
                            blocks
                        }
                    }
                    else -> c
                }
                result[idx] = msg.copy(content = newContent)
            }
        }

        return result
    }

    /** Explicit cache breakpoint value: Anthropic ephemeral prompt caching. */
    private fun buildSystemPayload(systemPrompt: String): Any? {
        if (systemPrompt.isBlank()) return null
        if (!cacheBreakpointsEnabled) return systemPrompt
        return listOf(
            mapOf(
                "type" to "text",
                "text" to systemPrompt,
                "cache_control" to CACHE_BREAKPOINT
            )
        )
    }

    /** Convert a Map<String, Any> (from Gson) to a JsonObject */
    private fun mapToJson(map: Map<String, Any>): JsonObject {
        val mutable = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        for ((k, v) in map) {
            mutable[k] = when (v) {
                is String -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    mapToJson(v as Map<String, Any>)
                }
                else -> JsonPrimitive(v.toString())
            }
        }
        return JsonObject(mutable)
    }

    /** Convert a JsonObject back to Map<String, Any> for Anthropic API */
    private fun jsonElementToMap(element: kotlinx.serialization.json.JsonElement): Any {
        return when (element) {
            is JsonObject -> element.mapValues { (_, v) -> jsonElementToMap(v) }
            is kotlinx.serialization.json.JsonArray -> element.map { jsonElementToMap(it) }
            is JsonPrimitive -> element.contentOrNull ?: ""
        }
    }

    private fun AgentMessage.UserMessage.toAnthropicUserContent(): Any {
        if (images.isEmpty()) return content

        val blocks = mutableListOf<AnthropicContentBlock>()
        if (content.isNotBlank()) {
            blocks.add(AnthropicContentBlock(type = "text", text = content))
        }
        images.forEach { image ->
            blocks.add(image.toAnthropicImageBlock())
        }
        return blocks
    }

    private fun AgentImage.toAnthropicImageBlock(): AnthropicContentBlock {
        return AnthropicContentBlock(
            type = "image",
            source = mapOf(
                "type" to "base64",
                "media_type" to mimeType,
                "data" to base64Data
            )
        )
    }

    private companion object {
        /** Explicit cache breakpoint: Anthropic ephemeral prompt caching. */
        val CACHE_BREAKPOINT = mapOf("type" to "ephemeral")

        /**
         * Anthropic's `input_tokens` counts only the cache-miss portion; total input adds cache_read / cache_creation,
         * aligned with OpenAI (`prompt_tokens` includes `cached_tokens`) and Gemini (`promptTokenCount`).
         * Context usage, compression triggers, cost, and cache hit rate are all computed on total input.
         */
        fun totalInputTokens(input: Int, cacheRead: Int?, cacheCreation: Int?): Int =
            input + (cacheRead ?: 0) + (cacheCreation ?: 0)

        /** Fallback max output tokens when model metadata lacks an output limit. */
        const val DEFAULT_MAX_TOKENS = 16384

        /** Minimum tokens reserved for the body when thinking is on (max_tokens must exceed the thinking budget). */
        const val MIN_CONTENT_TOKENS = 4096

        val gson = com.google.gson.Gson()
    }
}
