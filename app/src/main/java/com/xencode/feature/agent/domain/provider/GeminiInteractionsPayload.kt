package com.xencode.feature.agent.domain.provider

import com.xencode.feature.agent.data.remote.gemini.InteractionContent
import com.xencode.feature.agent.data.remote.gemini.InteractionStep
import com.xencode.feature.agent.domain.model.AgentImage
import com.xencode.feature.agent.domain.model.AgentMessage
import com.xencode.feature.agent.domain.tool.AgentTool
import com.xencode.feature.agent.domain.tool.ToolCall
import com.xencode.feature.agent.domain.tool.modelToolResultText
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Tool definitions -> Interactions flat function declarations; returns null when there are no tools (the `tools` field is omitted).
 *
 * Unlike generateContent: no more `[{functionDeclarations: [...]}]` wrapper; each tool is just
 * an object with `type: "function"` inside the `tools` array.
 */
internal fun buildInteractionsTools(tools: List<AgentTool>): List<Map<String, Any>>? =
    tools.takeIf { it.isNotEmpty() }?.map { tool ->
        mapOf(
            "type" to "function",
            "name" to tool.name,
            "description" to tool.description,
            "parameters" to tool.toJsonSchema()
        )
    }

/**
 * Session history -> the Interactions `input` step sequence (stateless mode; the request carries `store=false`).
 *
 * Unlike generateContent's `contents` (two roles, user/model, plus parts), this is a flat
 * timeline where model output, thinking, tool calls, and tool results are each independent steps.
 *
 * Pairing constraints match Responses and likewise must be guaranteed client-side: every `function_result` must find the
 * `function_call` with the same `call_id`, and every declared `function_call` must have a result. Results are absorbed per call here, which also
 * handles two kinds of dirty history:
 * - Results landing out of order (e.g. other tool results jumping ahead while askUserQuestion blocks) -> absorbed back after the call;
 * - Orphaned results (the preceding call was trimmed by context compression) -> dropped;
 * - Calls without results (e.g. the user declined execution) -> trimmed together with the call.
 *
 * **A turn's multiple `function_call` steps are written consecutively first, then all `function_result` steps together**, matching the official stateless
 * example (append the whole batch of model steps first, then the results).
 */
internal fun buildInteractionsInput(messages: List<AgentMessage>): List<Any> {
    val steps = mutableListOf<Any>()
    val consumed = BooleanArray(messages.size)
    for (i in messages.indices) {
        if (consumed[i]) continue
        when (val message = messages[i]) {
            is AgentMessage.UserMessage -> steps.add(
                mapOf(
                    "type" to InteractionStep.USER_INPUT,
                    "content" to message.toInteractionContent()
                )
            )

            is AgentMessage.AssistantMessage -> {
                val paired = message.toolCalls.mapNotNull { call ->
                    val resultIndex = (i + 1 until messages.size).firstOrNull { j ->
                        !consumed[j] && (messages[j] as? AgentMessage.ToolResultMessage)?.id == call.id
                    } ?: return@mapNotNull null
                    consumed[resultIndex] = true
                    call to (messages[resultIndex] as AgentMessage.ToolResultMessage)
                }
                steps.addAll(message.toModelSteps(paired.map { it.first }))
                for ((call, result) in paired) {
                    steps.add(
                        mapOf(
                            "type" to InteractionStep.FUNCTION_RESULT,
                            "call_id" to call.id,
                            "name" to call.name,
                            "result" to result.toInteractionResult()
                        )
                    )
                }
            }

            is AgentMessage.ToolResultMessage -> consumed[i] = true
        }
    }
    return steps
}

/**
 * One assistant turn -> the model step sequence.
 *
 * The official API requires replaying model-produced steps **verbatim** in stateless mode (`thought` signatures and function_calls
 * cannot be rebuilt), so the persisted snapshot is preferred. But verbatim replay presupposes every call of the turn found a result: once a call is
 * trimmed (e.g. the user declined), the snapshot's signatures no longer match the calls actually sent, and we fall back to rebuilding from the body.
 */
private fun AgentMessage.AssistantMessage.toModelSteps(keptCalls: List<ToolCall>): List<Any> {
    // Turns with images skip the snapshot: it would carry huge base64 (about 10MB for a 4K image) and blow up the database row, and a replay
    // without the images would break multi-turn editing. Rebuilding is preferred (such turns barely involve tools, so losing the thought signature is contained);
    // the image blocks are merged into model_output and replayed in full.
    decodeInteractionSteps(thinkingBlocksJson)
        ?.takeIf { keptCalls.size == toolCalls.size && images.isEmpty() }
        ?.let { return it }

    val steps = mutableListOf<Any>()
    val contentBlocks = buildList {
        if (content.isNotBlank()) add(textContent(content))
        images.forEach { add(it.toImageContent()) }
    }
    if (contentBlocks.isNotEmpty()) {
        steps.add(
            mapOf(
                "type" to InteractionStep.MODEL_OUTPUT,
                "content" to contentBlocks
            )
        )
    }
    keptCalls.forEach { call ->
        steps.add(
            mapOf(
                "type" to InteractionStep.FUNCTION_CALL,
                "id" to call.id,
                "name" to call.name,
                "arguments" to call.argumentsAsJson()
            )
        )
    }
    return steps
}

/** User message content: the body and each image become their own content block. */
private fun AgentMessage.UserMessage.toInteractionContent(): List<Map<String, Any>> {
    val blocks = mutableListOf<Map<String, Any>>()
    if (content.isNotBlank()) blocks.add(textContent(content))
    images.forEach { blocks.add(it.toImageContent()) }
    // The server rejects an empty array, so degrade to a single (possibly empty) text block.
    return blocks.ifEmpty { listOf(textContent(content)) }
}

/**
 * Tool results: returned as a `{"result": text}` object.
 *
 * Images produced by tools are not sent back: generated images were already shown to the user and saved, and feeding them into context only bloats tokens;
 * also, some relay gateways converting interactions to generateContent cannot handle array-shaped results
 * (they stuff the content array straight into function_response.response and fail with `cannot start list`).
 * Official Interactions results accept object/string/Content array; the object is the safest.
 */
private fun AgentMessage.ToolResultMessage.toInteractionResult(): Any {
    // File-type tools feed the model a condensed projection; the UI/persistence still use the full result.
    val modelText = modelResult
        ?: modelToolResultText(toolName, result)
        ?: result
    return if (modelText.isBlank()) {
        listOf(textContent(modelText))
    } else {
        mapOf("result" to modelText)
    }
}

/**
 * Verbatim snapshot of model-produced steps (JSON array text), stored in [AgentMessage.AssistantMessage.thinkingBlocksJson]
 * and replayed verbatim the next turn.
 *
 * Generated only when non-rebuildable content exists (`thought` signature / `function_call`); pure-text turns return null
 * so history takes the regular rebuild path, avoiding double-writing the body. `user_input` / `function_result` are echoes of input we sent ourselves,
 * filtered out here; keeping them would duplicate them next turn.
 */
internal fun snapshotInteractionSteps(steps: List<JsonObject>): String? {
    val generated = steps.filter { it.stepType() !in ECHOED_STEP_TYPES }
    val needsSnapshot = generated.any {
        it.stepType() == InteractionStep.THOUGHT || it.stepType() == InteractionStep.FUNCTION_CALL
    }
    if (!needsSnapshot) return null
    return JsonArray().apply { generated.forEach { add(it) } }.toString()
}

internal fun snapshotInteractionSteps(steps: JsonArray?): String? {
    if (steps == null) return null
    return snapshotInteractionSteps(steps.mapNotNull { it.takeIf { el -> el.isJsonObject }?.asJsonObject })
}

/** Snapshot -> step list; returns null when blank or corrupt, and the caller falls back to rebuilding. */
internal fun decodeInteractionSteps(json: String): List<JsonObject>? {
    if (json.isBlank()) return null
    return runCatching {
        JsonParser.parseString(json).asJsonArray
            .mapNotNull { it.takeIf { el -> el.isJsonObject }?.asJsonObject }
            .filter { it.stepType() !in ECHOED_STEP_TYPES }
            .takeIf { it.isNotEmpty() }
    }.getOrNull()
}

/**
 * Tool arguments -> Gson tree. Handing kotlinx's JsonObject straight to Gson would reflectively serialize its internal fields
 * (producing garbage like `{"body":...,"isString":true}`); it must be converted to text first and re-parsed by Gson.
 */
private fun ToolCall.argumentsAsJson(): JsonElement =
    runCatching {
        JsonParser.parseString(kotlinx.serialization.json.JsonObject(arguments).toString())
    }.getOrElse { JsonObject() }

/** Step types we assemble ourselves in session history that must not appear in the model-output snapshot. */
private val ECHOED_STEP_TYPES = setOf(InteractionStep.USER_INPUT, InteractionStep.FUNCTION_RESULT)

private fun textContent(text: String): Map<String, Any> =
    mapOf("type" to InteractionContent.TEXT, "text" to text)

private fun AgentImage.toImageContent(): Map<String, Any> = mapOf(
    "type" to InteractionContent.IMAGE,
    "mime_type" to mimeType,
    "data" to base64Data
)

private fun JsonObject.stepType(): String? =
    get("type")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
