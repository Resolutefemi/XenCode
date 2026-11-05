package com.xencode.feature.agent.domain.provider

import com.xencode.feature.agent.data.remote.openai.ResponsesEvent
import com.xencode.feature.agent.data.remote.openai.ResponsesItem
import com.xencode.feature.agent.data.remote.openai.ResponsesPart
import com.xencode.feature.agent.domain.model.AgentImage
import com.xencode.feature.agent.domain.tool.ToolCall
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Parsed result of the Responses body `output` array. */
internal data class ResponsesOutput(
    val text: String = "",
    val reasoning: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    /** Images the model produced via the server-side image_generation tool (`result` base64). */
    val images: List<AgentImage> = emptyList(),
    /** Snapshot of the official Responses reasoning item (encrypted_content and summary included), for safe stateless replay across turns. */
    val thinkingBlocksJson: String? = null
)

/** Token usage of a Responses request. */
internal data class ResponsesUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val cacheCreationTokens: Int = 0
)

/**
 * Parses the Responses `output` array: shared by non-streaming responses and streaming terminal events.
 *
 * Key difference from Chat Completions: tool calls are **top-level items** in output (`type: "function_call"`,
 * carrying their own `call_id` / `name` / `arguments`), not a part inside the assistant message content.
 * Looking in message.content the old way never finds tool calls.
 */
internal fun parseResponsesOutput(output: JsonArray?): ResponsesOutput {
    if (output == null) return ResponsesOutput()
    val text = StringBuilder()
    val reasoning = StringBuilder()
    val toolCalls = mutableListOf<ToolCall>()
    val images = mutableListOf<AgentImage>()
    var thinkingBlocksSnapshot: String? = null
    output.forEach { element ->
        // Upstream field types occasionally vary; one unparseable item must not sink the whole response
        runCatching {
            val item = element.asJsonObject
            when (item.str("type")) {
                ResponsesItem.MESSAGE -> item.arr("content")?.forEach { partEl ->
                    val part = partEl.asJsonObject
                    when (part.str("type")) {
                        ResponsesPart.OUTPUT_TEXT -> text.append(part.str("text").orEmpty())
                        // Refusal text lives in the refusal field; skipping it would leave the whole reply blank
                        ResponsesPart.REFUSAL -> text.append(part.str("refusal").orEmpty())
                    }
                }

                ResponsesItem.FUNCTION_CALL -> toolCalls.add(
                    ToolCall(
                        id = item.str("call_id") ?: item.str("id").orEmpty(),
                        name = item.str("name").orEmpty(),
                        arguments = parseToolArguments(item.str("arguments").orEmpty())
                    )
                )

                // Server-side image generation: the image arrives whole in the item's result (base64); no streaming delta.
                ResponsesItem.IMAGE_GENERATION_CALL -> {
                    item.str("result")?.takeIf { it.isNotEmpty() }?.let { result ->
                        images.add(AgentImage(mimeType = mimeForImageFormat(item.str("output_format")), base64Data = result))
                    }
                }

                // Reasoning may live in content (reasoning_text) or summary (summary_text), depending on the service;
                // both can be null (e.g. when no plain-text reasoning is returned).
                // Also extract the encrypted reasoning snapshot (encrypted_content) for safe stateless multi-turn replay.
                ResponsesItem.REASONING -> {
                    item.arr("content")?.forEach { partEl ->
                        val part = partEl.asJsonObject
                        if (part.str("type") == ResponsesPart.REASONING_TEXT) {
                            reasoning.append(part.str("text").orEmpty())
                        }
                    }
                    item.arr("summary")?.forEach { partEl ->
                        val part = partEl.asJsonObject
                        if (part.str("type") == ResponsesPart.SUMMARY_TEXT) {
                            reasoning.append(part.str("text").orEmpty())
                        }
                    }
                    val encContent = item.str("encrypted_content")?.takeIf { it.isNotEmpty() }
                    if (encContent != null && thinkingBlocksSnapshot == null) {
                        val snapshot = JsonObject().apply {
                            addProperty("type", ResponsesItem.REASONING)
                            addProperty("encrypted_content", encContent)
                            item.arr("summary")?.let { add("summary", it) } ?: add("summary", JsonArray())
                        }
                        thinkingBlocksSnapshot = snapshot.toString()
                    }
                }
            }
        }
    }
    return ResponsesOutput(text.toString(), reasoning.toString(), toolCalls, images, thinkingBlocksSnapshot)
}

/** image_generation_call `output_format` → MIME; defaults to the official png. */
private fun mimeForImageFormat(format: String?): String = when (format?.lowercase()) {
    "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> "image/png"
}

internal fun parseResponsesUsage(usage: JsonObject?): ResponsesUsage {
    if (usage == null) return ResponsesUsage()
    return ResponsesUsage(
        inputTokens = usage.int("input_tokens"),
        outputTokens = usage.int("output_tokens"),
        cachedInputTokens = usage.obj("input_tokens_details")?.int("cached_tokens") ?: 0,
        cacheCreationTokens = usage.obj("input_tokens_details")?.int("cache_write_tokens") ?: 0
    )
}

/**
 * Responses `status` → unified stopReason (reusing Chat Completions values so upper layers share one set of checks).
 * Output truncated by the token limit must map to `length`; otherwise [AIResponse.isTruncated] is false,
 * the agent loop never auto-continues, and the answer is silently cut short. Truncation reason values vary by service:
 * OpenAI's official docs show `max_tokens`, while DeepSeek / some compatible services use `max_output_tokens`.
 */
internal fun responsesStopReason(status: String?, incompleteReason: String?, hasToolCalls: Boolean): String? = when {
    status == "incomplete" && incompleteReason in TRUNCATION_REASONS -> "length"
    hasToolCalls -> "tool_calls"
    status == "completed" -> "stop"
    else -> status
}

private val TRUNCATION_REASONS = setOf("max_output_tokens", "max_tokens")

/** Incremental text produced by streaming events, for upper layers to push to the UI. */
internal sealed class ResponsesDelta {
    data class Text(val text: String) : ResponsesDelta()
    data class Reasoning(val text: String) : ResponsesDelta()

    /** A function_call just started and its tool name is known (arguments still streaming), so the UI can say early what is being called. */
    data class ToolCallDeclared(val name: String) : ResponsesDelta()
}

/**
 * Accumulator for Responses streaming semantic events.
 *
 * Events are grouped by `output_index`: `response.output_item.added` provides the function_call's call_id/name,
 * `response.function_call_arguments.delta` appends arguments piece by piece, and `response.output_item.done` /
 * `response.function_call_arguments.done` provides the full arguments (an overwrite guard against dropped deltas).
 * Terminal events (completed / incomplete) carry the full response object, used as a fallback to fill gaps and read usage and status;
 * failed throws [StreamApiException] directly for retry logic to decide on.
 */
internal class ResponsesStreamAccumulator {
    var receivedContent: Boolean = false
        private set

    private class CallAcc {
        var callId = ""
        var name = ""
        var args = StringBuilder()
    }

    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private val budget = StreamBudget()
    private var thinkingBlocksSnapshotJson: String? = null
    private val calls = LinkedHashMap<String, CallAcc>()
    /** Images arriving via output_item.done in the stream, deduplicated by item id. */
    private val images = LinkedHashMap<String, AgentImage>()
    /** Images parsed as a fallback from the terminal event (a gap-filler when stream delta events are missing). */
    private val finalImages = mutableListOf<AgentImage>()
    /** Tool calls parsed as a fallback from the terminal event (a gap-filler when stream delta events are missing). */
    private val finalCalls = mutableListOf<ToolCall>()
    private var status: String? = null
    private var incompleteReason: String? = null
    private var usage = ResponsesUsage()

    /** Whether a terminal event was received. A drop before one means the stream was truncated; the caller should treat it as an error. */
    var terminated = false
        private set

    /** Degraded-delivery flag for dropped streams: some body arrived but no terminal event, see [markNetworkTruncated]. */
    private var networkTruncated = false

    /** Accumulated body length, for logs and callers deciding whether degraded delivery is worthwhile after a drop. */
    val textLength: Int get() = text.length

    /** Whether degraded delivery is worthwhile after a drop: only when the body is non-blank (delivering just reasoning/a tool name is pointless). */
    fun hasSalvageableText(): Boolean = text.isNotBlank()

    /**
     * Degraded delivery on a dropped stream: part of the body arrived, but the connection ended first (peer EOF / proxy closed early / network switch).
     *
     * Once set, [toResponse] marks this content as truncated output (stopReason=length, [AIResponse.isTruncated]
     * true → the agent loop auto-continues), which beats failing the whole turn and discarding everything already streamed.
     */
    fun markNetworkTruncated() {
        if (terminated) return
        networkTruncated = true
        terminated = true
    }

    /** Processes an event object parsed from an SSE data line, returning this event's incremental text (null if none). */
    fun accept(event: JsonObject): ResponsesDelta? {
        when (event.str("type")) {
            ResponsesEvent.OUTPUT_TEXT_DELTA, ResponsesEvent.REFUSAL_DELTA -> {
                val delta = event.str("delta").orEmpty()
                if (delta.isEmpty()) return null
                budget.add(delta)
                text.append(delta)
                return ResponsesDelta.Text(delta)
            }

            ResponsesEvent.REASONING_TEXT_DELTA, ResponsesEvent.REASONING_SUMMARY_TEXT_DELTA -> {
                val delta = event.str("delta").orEmpty()
                if (delta.isEmpty()) return null
                budget.add(delta)
                reasoning.append(delta)
                return ResponsesDelta.Reasoning(delta)
            }

            ResponsesEvent.OUTPUT_ITEM_ADDED, ResponsesEvent.OUTPUT_ITEM_DONE -> {
                val item = event.obj("item") ?: return null
                when (item.str("type")) {
                    ResponsesItem.FUNCTION_CALL -> {
                        val acc = calls.getOrPut(event.callKey()) { CallAcc() }
                        item.str("call_id")?.takeIf { it.isNotEmpty() }?.let { acc.callId = it }
                        item.str("arguments")?.takeIf { it.isNotEmpty() }?.let { budget.add(it); acc.args = StringBuilder(it) }
                        item.str("name")?.takeIf { it.isNotEmpty() }?.let { name ->
                            val first = acc.name.isEmpty()
                            acc.name = name
                            // Tool name arrives before arguments: lets the UI make the status specific early (pushed once per call)
                            if (first) return ResponsesDelta.ToolCallDeclared(name)
                        }
                    }
                    ResponsesItem.REASONING -> {
                        val encContent = item.str("encrypted_content")?.takeIf { it.isNotEmpty() }
                        if (encContent != null) {
                            val snapshot = JsonObject().apply {
                                addProperty("type", ResponsesItem.REASONING)
                                addProperty("encrypted_content", encContent)
                                item.arr("summary")?.let { add("summary", it) } ?: add("summary", JsonArray())
                            }
                            thinkingBlocksSnapshotJson = snapshot.toString()
                        }
                    }
                    ResponsesItem.IMAGE_GENERATION_CALL -> {
                        item.str("result")?.takeIf { it.isNotEmpty() }?.let { result ->
                            val key = item.str("id") ?: event.callKey()
                            receivedContent = true
                            images[key] = AgentImage(mimeType = mimeForImageFormat(item.str("output_format")), base64Data = result)
                        }
                    }
                }
            }

            ResponsesEvent.FUNCTION_CALL_ARGS_DELTA -> {
                val delta = event.str("delta").orEmpty()
                if (delta.isEmpty()) return null
                budget.add(delta)
                calls.getOrPut(event.callKey()) { CallAcc() }.args.append(delta)
            }

            ResponsesEvent.FUNCTION_CALL_ARGS_DONE -> {
                val full = event.str("arguments").orEmpty()
                if (full.isEmpty()) return null
                budget.add(full)
                calls.getOrPut(event.callKey()) { CallAcc() }.args = StringBuilder(full)
            }

            ResponsesEvent.COMPLETED, ResponsesEvent.INCOMPLETE -> {
                // Set the flag first: terminal semantics must not be affected by response-object parse errors, or callers would read to the stream end and falsely report a drop.
                terminated = true
                absorbFinalResponse(event.obj("response"))
            }

            ResponsesEvent.FAILED -> {
                terminated = true
                val response = event.obj("response")
                absorbFinalResponse(response)
                val error = response?.obj("error") ?: event.obj("error")
                throw StreamApiException(
                    code = error?.str("code"),
                    message = error?.str("message") ?: "Responses stream ended with response.failed"
                )
            }

            // Transport-layer error events: code / message sit on the event itself (not nested in an error object).
            ResponsesEvent.ERROR, ResponsesEvent.RESPONSE_ERROR -> throw StreamApiException(
                code = event.str("code"),
                message = event.str("message") ?: "Responses stream returned an error event"
            )
        }
        return null
    }

    fun toResponse(): AIResponse {
        val streamed = calls.values
            .filter { it.callId.isNotEmpty() || it.name.isNotEmpty() }
            // On a dropped stream the arguments are almost certainly half-finished: running a tool (write file/run command) with broken arguments is far riskier than dropping the call.
            .filter { !networkTruncated || looksLikeCompleteJsonObject(it.args.toString()) }
            .map { ToolCall(id = it.callId, name = it.name, arguments = parseToolArguments(it.args.toString())) }
        val toolCalls = streamed + finalCalls.filter { call ->
            streamed.none { it.id == call.id && call.id.isNotEmpty() }
        }
        return AIResponse(
            content = text.toString(),
            toolCalls = toolCalls,
            stopReason = if (networkTruncated) STREAM_TRUNCATED_STOP_REASON else responsesStopReason(status, incompleteReason, toolCalls.isNotEmpty()),
            reasoning = reasoning.toString().takeIf { it.isNotEmpty() },
            thinkingBlocksJson = thinkingBlocksSnapshotJson,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cachedInputTokens = usage.cachedInputTokens,
            cacheCreationTokens = usage.cacheCreationTokens,
            images = images.values.filter { it.base64Data.isNotEmpty() }.ifEmpty { finalImages }
        )
    }

    /** The terminal event carries the full response: read status/usage, and use the top-level output to fill whatever the stream missed. */
    private fun absorbFinalResponse(response: JsonObject?) {
        if (response == null) return
        status = response.str("status")
        incompleteReason = response.obj("incomplete_details")?.str("reason")
        usage = parseResponsesUsage(response.obj("usage"))

        val parsed = parseResponsesOutput(response.arr("output"))
        if (text.isEmpty()) { budget.add(parsed.text); text.append(parsed.text) }
        if (reasoning.isEmpty()) { budget.add(parsed.reasoning); reasoning.append(parsed.reasoning) }
        if (thinkingBlocksSnapshotJson == null) thinkingBlocksSnapshotJson = parsed.thinkingBlocksJson
        finalCalls.clear()
        finalCalls.addAll(parsed.toolCalls)
        finalImages.clear()
        finalImages.addAll(parsed.images)
    }

    private fun JsonObject.callKey(): String =
        str("output_index") ?: str("item_id") ?: "0"
}

private fun JsonObject.str(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

private fun JsonObject.int(name: String): Int =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt ?: 0

// The two below replace Gson's getAsJsonArray / getAsJsonObject: those cast blindly and throw
// ClassCastException on null (e.g. a reasoning item's `content: null` or a missing `output` in the failed event).
private fun JsonObject.arr(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject
