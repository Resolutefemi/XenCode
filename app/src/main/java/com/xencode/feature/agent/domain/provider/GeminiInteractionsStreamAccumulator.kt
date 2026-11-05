package com.xencode.feature.agent.domain.provider

import com.xencode.feature.agent.data.remote.gemini.InteractionContent
import com.xencode.feature.agent.data.remote.gemini.InteractionDelta
import com.xencode.feature.agent.data.remote.gemini.InteractionEvent
import com.xencode.feature.agent.data.remote.gemini.InteractionStep
import com.xencode.feature.agent.data.remote.gemini.TERMINAL_STATUSES
import com.xencode.feature.agent.domain.model.AgentImage
import com.xencode.feature.agent.domain.tool.ToolCall
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Parse result of the Interactions `steps` array. */
internal data class InteractionsOutput(
    val text: String = "",
    val reasoning: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    /** Images produced by the model (Nano Banana image model); each element carries base64 data. Always empty for non-image models. */
    val images: List<AgentImage> = emptyList(),
    /** Verbatim snapshot of model-produced steps, replayed as-is in the next stateless turn; null when no snapshot is needed. */
    val stepsSnapshotJson: String? = null
)

/** Interactions token usage. */
internal data class InteractionsUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0
)

/**
 * usage fields are all renamed relative to generateContent, and `total_output_tokens` **excludes** thinking tokens
 * (official example: `total_tokens = input + output + thought`). Thinking is billed at the output rate, so the two
 * added together are the true output.
 */
internal fun parseInteractionsUsage(usage: JsonObject?): InteractionsUsage {
    if (usage == null) return InteractionsUsage()
    return InteractionsUsage(
        inputTokens = usage.int("total_input_tokens"),
        outputTokens = usage.int("total_output_tokens") + usage.int("total_thought_tokens"),
        cachedInputTokens = usage.int("total_cached_tokens")
    )
}

/**
 * Parse the Interactions `steps` array: shared by non-streaming responses and the streaming terminal-event fallback.
 *
 * Key difference from generateContent: body, thinking, and tool calls are no longer siblings inside one `parts` array
 * but independent steps on a timeline. A tool call is a `function_call` step (with its own `id` / `name` /
 * `arguments` object); looking inside content the old way never parses it out.
 */
internal fun parseInteractionSteps(steps: JsonArray?): InteractionsOutput {
    if (steps == null) return InteractionsOutput()
    val text = StringBuilder()
    val reasoning = StringBuilder()
    val toolCalls = mutableListOf<ToolCall>()
    val images = mutableListOf<AgentImage>()
    steps.forEach { element ->
        // One step failing to parse must not ruin the whole response
        runCatching {
            val step = element.asJsonObject
            when (step.str("type")) {
                InteractionStep.MODEL_OUTPUT -> step.arr("content")?.forEach { block ->
                    val content = block.asJsonObject
                    when (content.str("type")) {
                        InteractionContent.TEXT -> text.append(content.str("text").orEmpty())
                        InteractionContent.IMAGE -> content.toAgentImage()?.let { images.add(it) }
                    }
                }

                InteractionStep.THOUGHT -> step.arr("summary")?.forEach { block ->
                    val content = block.asJsonObject
                    if (content.str("type") == InteractionContent.TEXT) {
                        reasoning.append(content.str("text").orEmpty())
                    }
                }

                InteractionStep.FUNCTION_CALL -> {
                    val name = step.str("name").orEmpty()
                    toolCalls.add(
                        ToolCall(
                            // Parallel calls pair by id; fall back to the function name only when missing (calling the same tool twice would collide).
                            id = step.str("id")?.takeIf { it.isNotBlank() } ?: name,
                            name = name,
                            arguments = parseToolArguments(step.obj("arguments")?.toString().orEmpty())
                        )
                    )
                }
            }
        }
    }
    return InteractionsOutput(
        text = text.toString(),
        reasoning = reasoning.toString(),
        toolCalls = toolCalls,
        images = images,
        stepsSnapshotJson = snapshotInteractionSteps(steps)
    )
}

/**
 * Interactions `status` -> unified stopReason.
 *
 * `incomplete` (hit `max_output_tokens`) must map to `length`: only after aligning with Chat Completions values
 * does [AIResponse.isTruncated] hold and the Agent loop auto-continue; otherwise the answer looks silently truncated.
 * Conversely, `incomplete` must not go straight into [AIResponse.TRUNCATION_STOP_REASONS]: the Responses API
 * status of the same name also covers non-truncation causes like content_filter, and sharing the word would misjudge those as truncation.
 *
 * Other statuses pass through as-is: `requires_action` means tools await execution; `failed` / `budget_exceeded` hit
 * [AIResponse.ABORT_STOP_REASONS] and are explained to the user by upper layers instead of silently completing.
 */
internal fun interactionStopReason(status: String?): String? =
    if (status == "incomplete") "length" else status

/** Incremental text produced by streaming events, for upper layers to push to the UI. */
internal sealed class InteractionsDelta {
    data class Text(val text: String) : InteractionsDelta()
    data class Reasoning(val text: String) : InteractionsDelta()

    /** A function_call has just started and the tool name is known (arguments still streaming), so the UI can say early what is being called. */
    data class ToolCallDeclared(val name: String) : InteractionsDelta()
}

/**
 * Accumulator for Interactions streaming events.
 *
 * The event discriminator field is **`event_type`** (the migration guide's examples use `type`, contradicting the reference docs; both are accepted here).
 * Step events are grouped by `index`: `step.start` gives the step type and the function_call id/name,
 * `step.delta` streams body / thought summary / thought signature / tool arguments piece by piece, and `step.stop` wraps up.
 *
 * The stream **sends no `[DONE]`**, and **with tools pending the server stops at `requires_action` and never sends `completed`**,
 * so completion is judged by whether the interaction's status has reached a terminal state ([TERMINAL_STATUSES]); waiting only for completed
 * would read all the way to end-of-stream and be misjudged as a broken stream.
 */
internal class GeminiInteractionsStreamAccumulator {
    var receivedContent: Boolean = false
        private set

    private class StepAcc {
        var type: String? = null
        var id = ""
        var name = ""
        var signature: String? = null
        val text = StringBuilder()
        val summary = StringBuilder()

        /** Argument fragments accumulated piece by piece from `arguments_delta`. */
        val argsDelta = StringBuilder()

        /** Complete arguments given directly by `step.start` (some implementations skip the delta). */
        var argsComplete: String? = null

        /** Raw step object from `step.start`: steps we do not parse (e.g. server tools) are kept verbatim for replay. */
        var raw: JsonObject? = null

        /** When step.start is missing, infer the type from the deltas received so far. */
        fun effectiveType(): String? = type ?: when {
            text.isNotEmpty() -> InteractionStep.MODEL_OUTPUT
            summary.isNotEmpty() || signature != null -> InteractionStep.THOUGHT
            name.isNotEmpty() -> InteractionStep.FUNCTION_CALL
            else -> null
        }

        /** Prefer the accumulated delta value; fall back to the complete arguments from step.start only when it is missing or malformed. */
        fun arguments(): kotlinx.serialization.json.JsonObject =
            parseArgsOrNull(argsDelta.toString())
                ?: parseArgsOrNull(argsComplete.orEmpty())
                ?: kotlinx.serialization.json.JsonObject(emptyMap())
    }

    private val steps = LinkedHashMap<Int, StepAcc>()
    private val budget = StreamBudget()
    private var status: String? = null
    private var statusDetail: String? = null
    private var usage = InteractionsUsage()

    /** Output parsed from the terminal event as a fallback (fills gaps when stream deltas were missing). */
    private var finalOutput: InteractionsOutput? = null

    /** Whether a terminal state has been read. A stream dropping before one means truncation; the caller should treat it as an error. */
    var terminated = false
        private set

    /**
     * The stream was cut before a terminal state (peer EOF / proxy closed early / network switch), with some body already deliverable degraded.
     * Mutually exclusive with [terminated]: never set once a terminal state was reached.
     */
    var truncatedByNetwork = false
        private set

    /** Set by the caller after a broken stream; ignored if a terminal state was reached (normal end, must not be marked truncated). */
    fun markTruncatedByNetwork() {
        if (!terminated) truncatedByNetwork = true
    }

    /** Length of accumulated body text, for the caller to judge whether degraded delivery is worthwhile after a broken stream. */
    val streamedTextLength: Int
        get() = steps.values.sumOf { acc ->
            if (acc.effectiveType() == InteractionStep.MODEL_OUTPUT) acc.text.length else 0
        }

    /** Process one event object parsed from an SSE data line; returns the incremental text it produced (null if none). */
    fun accept(event: JsonObject): InteractionsDelta? {
        // usage may ride on any event's metadata; absorb it unconditionally first.
        event.obj("metadata")?.obj("total_usage")?.let { usage = parseInteractionsUsage(it) }

        val eventType = event.str("event_type") ?: event.str("type")
        when {
            eventType == InteractionEvent.STEP_START -> {
                val step = event.obj("step") ?: return null
                val acc = steps.getOrPut(event.int("index")) { StepAcc() }
                acc.type = step.str("type")
                acc.raw = step
                step.str("id")?.takeIf { it.isNotBlank() }?.let { acc.id = it }
                // A function-call step's tool name arrives before the arguments: let the UI swap the generic "thinking" state for a concrete scene early.
                // The name is recorded but not returned yet: step.start may also carry full arguments / body as a fallback,
                // and returning early here would drop them; return once at the end of this branch.
                var declaredName: String? = null
                step.str("name")?.takeIf { it.isNotBlank() }?.let { name ->
                    if (acc.name.isBlank()) declaredName = name
                    acc.name = name
                }
                step.str("signature")?.let { acc.signature = it }
                step.obj("arguments")?.let { budget.add(it.toString()); acc.argsComplete = it.toString() }
                // Fallback: some implementations put the full body in step.start's content (with no delta afterwards),
                // in which case text deltas never see it. Extract only when nothing has accumulated yet, to avoid double-writing with deltas.
                if (acc.text.isEmpty()) {
                    step.arr("content")?.forEach { block ->
                        val content = block.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                        if (content.str("type") == InteractionContent.TEXT) {
                            val text = content.str("text").orEmpty()
                            budget.add(text)
                            acc.text.append(text)
                            if (text.isNotEmpty()) receivedContent = true
                        }
                    }
                }
                step.arr("content")?.forEach { block ->
                    val content = block.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    if (content.str("type") == InteractionContent.IMAGE && content.toAgentImage() != null) {
                        receivedContent = true
                    }
                }
                declaredName?.let { return InteractionsDelta.ToolCallDeclared(it) }
            }

            eventType == InteractionEvent.STEP_DELTA -> {
                val delta = event.obj("delta") ?: return null
                val acc = steps.getOrPut(event.int("index")) { StepAcc() }
                when (delta.str("type")) {
                    InteractionDelta.TEXT -> {
                        val text = delta.str("text").orEmpty()
                        if (text.isEmpty()) return null
                        budget.add(text)
                        acc.text.append(text)
                        return InteractionsDelta.Text(text)
                    }

                    // Thought-summary text lives in `content.text` (a Content object); some implementations use a flat `text` instead.
                    InteractionDelta.THOUGHT_SUMMARY, InteractionDelta.THOUGHT -> {
                        val text = delta.obj("content")?.str("text") ?: delta.str("text").orEmpty()
                        if (text.isEmpty()) return null
                        budget.add(text)
                        acc.summary.append(text)
                        return InteractionsDelta.Reasoning(text)
                    }

                    InteractionDelta.THOUGHT_SIGNATURE ->
                        delta.str("signature")?.let { acc.signature = it }

                    InteractionDelta.ARGUMENTS, InteractionDelta.ARGUMENTS_LEGACY -> {
                        val args = delta.str("arguments") ?: delta.str("partial_arguments").orEmpty()
                        budget.add(args)
                        acc.argsDelta.append(args)
                    }
                }
            }

            eventType == InteractionEvent.STEP_STOP ->
                event.obj("usage")?.let { usage = parseInteractionsUsage(it) }

            // Transport-layer / platform error: set the terminated flag first, so the caller does not read to end-of-stream and misreport a broken stream.
            eventType == InteractionEvent.ERROR -> {
                terminated = true
                val error = event.obj("error")
                throw StreamApiException(
                    code = error?.str("code"),
                    message = error?.str("message") ?: "Interactions stream returned an error event"
                )
            }

            eventType != null && eventType.startsWith(InteractionEvent.LIFECYCLE_PREFIX) ->
                absorbLifecycle(eventType, event)
        }
        return null
    }

    /**
     * Lifecycle events: the status may sit in `interaction.status` (created / completed) or at the event's
     * top level (`interaction.status_update`); names that encode the status, like `interaction.requires_action`,
     * must also be recognized, or tool turns never reach a terminal state.
     *
     * The event name is only a fallback: an `interaction.completed` payload may say `incomplete` / `failed`
     * (completed with an incomplete result); overwriting from the event name would erase truncation and failure into normal completion.
     */
    private fun absorbLifecycle(eventType: String, event: JsonObject) {
        val interaction = event.obj("interaction")
        val payloadStatus = interaction?.str("status") ?: event.str("status")
        val nameStatus = eventType.removePrefix(InteractionEvent.LIFECYCLE_PREFIX)
            .takeIf { it in TERMINAL_STATUSES }
        (payloadStatus ?: nameStatus)?.let { status = it }
        interaction?.obj("usage")?.let { usage = parseInteractionsUsage(it) }
        interaction?.arr("steps")?.takeIf { it.size() > 0 }?.let { finalOutput = parseInteractionSteps(it) }
        // Platform error details: when status=failed the body is usually empty and this is the only displayable reason.
        interaction?.arr("errors")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?.str("message")?.let { statusDetail = it }
        if (status in TERMINAL_STATUSES) terminated = true
    }

    fun toResponse(): AIResponse {
        val streamed = buildStreamedOutput()
        val toolCalls = streamed.toolCalls.ifEmpty { finalOutput?.toolCalls.orEmpty() }
        // In streaming, images arrive whole (including full payloads in step.start) and produce no incremental deltas; the terminal event also carries a fallback parse.
        val images = streamed.images.ifEmpty { finalOutput?.images.orEmpty() }
        // Degraded delivery after a broken stream: mark stopReason as length so the Agent loop auto-continues instead of failing the turn.
        val truncated = truncatedByNetwork && status !in TERMINAL_STATUSES
        return AIResponse(
            content = streamed.text.ifEmpty { finalOutput?.text.orEmpty() },
            toolCalls = toolCalls,
            stopReason = if (truncated) STREAM_TRUNCATED_STOP_REASON else interactionStopReason(status),
            stopDetail = statusDetail,
            reasoning = streamed.reasoning.ifEmpty { finalOutput?.reasoning.orEmpty() }.takeIf { it.isNotEmpty() },
            thinkingBlocksJson = streamed.stepsSnapshotJson ?: finalOutput?.stepsSnapshotJson,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cachedInputTokens = usage.cachedInputTokens,
            images = images
        )
    }

    /**
     * Rebuild the steps accumulated by index into output plus a replayable step snapshot.
     * The snapshot must include `thought` signatures and complete `function_call`s: replaying these verbatim in stateless mode
     * is what continues the reasoning context ([snapshotInteractionSteps] strips pure-text turns that need no snapshot).
     */
    private fun buildStreamedOutput(): InteractionsOutput {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        val images = mutableListOf<AgentImage>()
        val rebuilt = mutableListOf<JsonObject>()
        steps.toSortedMap().values.forEach { acc ->
            when (acc.effectiveType()) {
                InteractionStep.MODEL_OUTPUT -> {
                    text.append(acc.text)
                    // step.start's full payload may directly contain image content (images produce no streaming deltas and arrive whole).
                    acc.raw?.arr("content")?.forEach { block ->
                        val content = block.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                        if (content.str("type") == InteractionContent.IMAGE) content.toAgentImage()?.let { images.add(it) }
                    }
                    rebuilt.add(modelOutputStep(acc.text.toString()))
                }

                InteractionStep.THOUGHT -> {
                    reasoning.append(acc.summary)
                    rebuilt.add(thoughtStep(acc.signature, acc.summary.toString()))
                }

                InteractionStep.FUNCTION_CALL -> {
                    if (acc.name.isEmpty()) return@forEach
                    // On a broken stream the arguments almost certainly stop mid-JSON: running a tool (write file, run command) with
                    // mangled arguments is far more dangerous than dropping the call; keep only calls that parse into complete objects.
                    if (truncatedByNetwork && acc.argsDelta.isNotEmpty() &&
                        parseArgsOrNull(acc.argsDelta.toString()) == null
                    ) {
                        return@forEach
                    }
                    val callId = acc.id.ifEmpty { acc.name }
                    val args = acc.arguments()
                    toolCalls.add(ToolCall(id = callId, name = acc.name, arguments = args))
                    rebuilt.add(functionCallStep(callId, acc.name, args))
                }

                // Server tools (google_search_call etc.) are not parsed here, but they carry signatures; keep them verbatim for replay.
                else -> acc.raw?.let { rebuilt.add(it) }
            }
        }
        return InteractionsOutput(
            text = text.toString(),
            reasoning = reasoning.toString(),
            toolCalls = toolCalls,
            images = images,
            stepsSnapshotJson = snapshotInteractionSteps(rebuilt)
        )
    }

    private fun textBlock(text: String) = JsonObject().apply {
        addProperty("type", InteractionContent.TEXT)
        addProperty("text", text)
    }

    private fun modelOutputStep(text: String) = JsonObject().apply {
        addProperty("type", InteractionStep.MODEL_OUTPUT)
        add("content", JsonArray().apply { add(textBlock(text)) })
    }

    private fun thoughtStep(signature: String?, summary: String) = JsonObject().apply {
        addProperty("type", InteractionStep.THOUGHT)
        signature?.let { addProperty("signature", it) }
        if (summary.isNotEmpty()) add("summary", JsonArray().apply { add(textBlock(summary)) })
    }

    private fun functionCallStep(
        id: String,
        name: String,
        arguments: kotlinx.serialization.json.JsonObject
    ) = JsonObject().apply {
        addProperty("type", InteractionStep.FUNCTION_CALL)
        addProperty("id", id)
        addProperty("name", name)
        add("arguments", JsonParser.parseString(arguments.toString()))
    }
}

private fun parseArgsOrNull(raw: String): kotlinx.serialization.json.JsonObject? =
    raw.trim().takeIf { it.isNotEmpty() }?.let {
        runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
    }

private fun JsonObject.str(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

/** Interactions image content block -> [AgentImage]. Returns null when data is missing (abnormal response). */
private fun JsonObject.toAgentImage(): AgentImage? {
    val data = str("data").orEmpty()
    if (data.isEmpty()) return null
    return AgentImage(
        mimeType = str("mime_type") ?: str("mimeType") ?: "image/png",
        base64Data = data
    )
}

private fun JsonObject.int(name: String): Int =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt ?: 0

// The two below replace Gson's getAsJsonArray / getAsJsonObject: those cast directly and throw
// ClassCastException on null (e.g. a thought step's missing `summary`).
private fun JsonObject.arr(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject
