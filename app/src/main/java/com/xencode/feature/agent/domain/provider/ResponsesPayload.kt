package com.xencode.feature.agent.domain.provider

import com.xencode.feature.agent.data.remote.openai.ResponsesItem
import com.xencode.feature.agent.data.remote.openai.ResponsesPart
import com.xencode.feature.agent.data.remote.openai.ResponsesToolDefinition
import com.xencode.feature.agent.domain.model.AgentImage
import com.xencode.feature.agent.domain.model.AgentMessage
import com.xencode.feature.agent.domain.tool.AgentTool
import com.xencode.feature.agent.domain.tool.modelToolResultText
import com.google.gson.JsonParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Parse the model's tool-argument JSON string into a JsonObject; falls back to an empty object when blank or invalid. */
internal fun parseToolArguments(raw: String): JsonObject {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return JsonObject(emptyMap())
    return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrElse { JsonObject(emptyMap()) }
}

/** Tool definitions -> flat Responses function definitions; returns null when there are no tools (the `tools` field is omitted). */
internal fun buildResponsesTools(tools: List<AgentTool>): List<ResponsesToolDefinition>? =
    tools.takeIf { it.isNotEmpty() }?.map { tool ->
        ResponsesToolDefinition(
            name = tool.name,
            description = tool.description,
            parameters = tool.toJsonSchema()
        )
    }

/**
 * Session history -> the Responses `input` item sequence.
 *
 * Unlike Chat Completions' three role-based message kinds, Responses splits tool round-trips into standalone items:
 * the model's call is a `function_call` (identified by `call_id`) and the execution result is a `function_call_output`,
 * neither attached to an assistant message or a `role:"tool"` message.
 *
 * Pairing constraints match Chat Completions and likewise must be guaranteed client-side: every `function_call_output` must
 * find the `function_call` with the same `call_id`, and every declared `function_call` must have a result. Results are absorbed
 * per call here, which also handles two kinds of dirty history:
 * - Results landing out of order (e.g. other tool results jumping ahead while askUserQuestion blocks) -> absorbed back after the call;
 * - Orphaned results (the preceding call was trimmed by context compression) -> dropped;
 * - Calls without results (e.g. the user declined execution) -> trimmed together with the call.
 *
 * **A turn's multiple `function_call` items must be written consecutively first, then all `function_call_output` items together**; calls
 * and results must not alternate: the server merges `function_call` items into the adjacent assistant message, and once a
 * `function_call_output` (equivalent to a tool message) sits in between, later calls are treated as a new assistant turn. For DeepSeek
 * thinking mode those extra turns have no reasoning item, producing
 * 400 The `reasoning_text` in the thinking mode must be passed back to the API.
 * Grouped writing also matches Chat Completions' "one assistant with N tool_calls + N tool messages".
 *
 * Reasoning content is replayed as a standalone `reasoning` item only when [includeReasoningItems] is true, right before the assistant
 * content it belongs to (matching the model output item order; the server merges it into the adjacent assistant message); when reasoning is
 * empty, an empty-text placeholder is sent. The switch exists because the two vendors demand opposite things:
 * - DeepSeek thinking mode: whenever the request carries `tools`, every historical assistant turn must replay its full reasoning
 *   (even if that turn made no tool calls), or 400; it only accepts plaintext `content` and does not support summary / encrypted_content.
 * - OpenAI's official reasoning item must carry `id`/`summary` and `encrypted_content`,
 *   and is replayed only when a valid ciphertext is stored; if missing it is skipped outright, preventing a plaintext-only item from triggering a server 400.
 */
internal fun buildResponsesInput(
    systemPrompt: String,
    systemRole: String,
    messages: List<AgentMessage>,
    includeReasoningItems: Boolean = false
): List<Map<String, Any?>> {
    val items = mutableListOf<Map<String, Any?>>()
    if (systemPrompt.isNotBlank()) {
        items.add(mapOf("role" to systemRole, "content" to systemPrompt))
    }

    val consumed = BooleanArray(messages.size)
    for (i in messages.indices) {
        if (consumed[i]) continue
        when (val message = messages[i]) {
            is AgentMessage.UserMessage -> items.add(
                mapOf("role" to "user", "content" to message.toResponsesContent())
            )

            is AgentMessage.AssistantMessage -> {
                // Pair calls with results first: calls without results are trimmed (e.g. the user declined); if the whole
                // turn is trimmed and has no body, no item is written for it and the reasoning has nowhere to attach.
                val paired = message.toolCalls.mapNotNull { call ->
                    val resultIndex = (i + 1 until messages.size).firstOrNull { j ->
                        !consumed[j] && (messages[j] as? AgentMessage.ToolResultMessage)?.id == call.id
                    } ?: return@mapNotNull null
                    consumed[resultIndex] = true
                    call to (messages[resultIndex] as AgentMessage.ToolResultMessage)
                }
                // Thinking-mode services require the previous turn's reasoning to be replayed verbatim; missing it yields 400:
                // The `reasoning_text` in the thinking mode must be passed back to the API.
                val hasBody = message.content.isNotBlank() || paired.isNotEmpty()
                val encryptedReasoning = decodeResponsesEncryptedReasoning(message.thinkingBlocksJson)
                if (hasBody) {
                    if (encryptedReasoning != null) {
                        // Prefer replaying the OpenAI official/generic Responses encrypted snapshot (with summary and encrypted_content)
                        items.add(encryptedReasoning)
                    } else if (includeReasoningItems) {
                        // DeepSeek thinking mode: replay plaintext reasoning_text
                        items.add(
                            mapOf(
                                "type" to ResponsesItem.REASONING,
                                "content" to listOf(
                                    mapOf(
                                        "type" to ResponsesPart.REASONING_TEXT,
                                        "text" to message.reasoning
                                    )
                                )
                            )
                        )
                    }
                }
                if (message.content.isNotBlank()) {
                    items.add(
                        mapOf(
                            "role" to "assistant",
                            "content" to listOf(
                                mapOf("type" to ResponsesPart.OUTPUT_TEXT, "text" to message.content)
                            )
                        )
                    )
                }
                for ((call, _) in paired) {
                    items.add(
                        mapOf(
                            "type" to ResponsesItem.FUNCTION_CALL,
                            "call_id" to call.id,
                            "name" to call.name,
                            "arguments" to JsonObject(call.arguments).toString()
                        )
                    )
                }
                for ((call, result) in paired) {
                    items.add(
                        mapOf(
                            "type" to ResponsesItem.FUNCTION_CALL_OUTPUT,
                            "call_id" to call.id,
                            "output" to result.toResponsesOutput()
                        )
                    )
                }
            }

            is AgentMessage.ToolResultMessage -> consumed[i] = true
        }
    }
    return items
}

/**
 * Deserialize an OpenAI official Responses-spec encrypted reasoning item from the snapshot JSON.
 * Strictly following the OpenCode / OpenAI defensive rules: it may be replayed only when it contains a non-empty `encrypted_content` string;
 * otherwise return null, never sending a broken reasoning item that makes the server throw a 400.
 */
internal fun decodeResponsesEncryptedReasoning(thinkingBlocksJson: String?): Map<String, Any?>? {
    if (thinkingBlocksJson.isNullOrBlank()) return null
    return runCatching {
        val obj = JsonParser.parseString(thinkingBlocksJson).asJsonObject
        if (obj.get("type")?.asString != ResponsesItem.REASONING) return null
        val encryptedContent = obj.get("encrypted_content")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
        if (encryptedContent.isNullOrBlank()) return null
        val summaryArr = obj.get("summary")?.takeIf { it.isJsonArray }?.asJsonArray
        val summaryList = summaryArr?.mapNotNull { el ->
            if (el.isJsonObject) {
                val o = el.asJsonObject
                val map = mutableMapOf<String, Any>()
                o.get("type")?.takeIf { !it.isJsonNull }?.asString?.let { map["type"] = it }
                o.get("text")?.takeIf { !it.isJsonNull }?.asString?.let { map["text"] = it }
                map.takeIf { it.isNotEmpty() }
            } else null
        } ?: emptyList()
        mapOf(
            "type" to ResponsesItem.REASONING,
            "summary" to summaryList,
            "encrypted_content" to encryptedContent
        )
    }.getOrNull()
}

/** User message content: plain text is given as a string; with images it becomes an input_text / input_image part list. */
private fun AgentMessage.UserMessage.toResponsesContent(): Any {
    if (images.isEmpty()) return content
    val parts = mutableListOf<Map<String, Any>>()
    if (content.isNotBlank()) {
        parts.add(mapOf("type" to ResponsesPart.INPUT_TEXT, "text" to content))
    }
    images.forEach { parts.add(it.toResponsesImagePart()) }
    return parts
}

/** Tool results: plain text is given as a string; with images a part list is used (Responses allows images inside function_call_output). */
private fun AgentMessage.ToolResultMessage.toResponsesOutput(): Any {
    // File-type tools feed the model a condensed projection; the UI/persistence still use the full result.
    val modelText = modelResult
        ?: modelToolResultText(toolName, result)
        ?: result
    if (images.isEmpty()) return modelText
    val parts = mutableListOf<Map<String, Any>>()
    if (modelText.isNotBlank()) {
        parts.add(mapOf("type" to ResponsesPart.INPUT_TEXT, "text" to modelText))
    }
    images.forEach { parts.add(it.toResponsesImagePart()) }
    return parts
}

private fun AgentImage.toResponsesImagePart(): Map<String, Any> = mapOf(
    "type" to ResponsesPart.INPUT_IMAGE,
    "image_url" to "data:$mimeType;base64,$base64Data",
    "detail" to "auto"
)
