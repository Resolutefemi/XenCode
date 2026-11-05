package com.xencode.feature.agent.data.remote.openai

/**
 * Function tool definition for the Responses API: fields are flattened directly onto the item, without Chat Completions'
 * `function` nesting layer. Sending the nested structure gets rejected by the server with "tools[0]: missing field `name`".
 */
data class ResponsesToolDefinition(
    val type: String = "function",
    val name: String,
    val description: String,
    val parameters: Map<String, Any>,
    /**
     * In the official schema `strict` carries no optional marker; always send false: strict mode requires the input schema
     * to satisfy the structured-output subset (all fields required + additionalProperties:false), which tools cannot guarantee.
     */
    val strict: Boolean = false
)

/**
 * Semantic event types for the Responses streaming SSE.
 * Unlike Chat Completions' delta frames, the stream ends with one of [COMPLETED] / [INCOMPLETE] / [FAILED];
 * **it does not send `data: [DONE]`**, so termination can only be detected via the terminal events.
 */
object ResponsesEvent {
    const val OUTPUT_TEXT_DELTA = "response.output_text.delta"
    /** Model refusal: the refusal explanation arrives via a separate event and a separate part; it never appears in output_text. */
    const val REFUSAL_DELTA = "response.refusal.delta"
    const val REASONING_TEXT_DELTA = "response.reasoning_text.delta"
    /** Thinking summaries from official reasoning models (o series / gpt-5 series) use this event, not reasoning_text. */
    const val REASONING_SUMMARY_TEXT_DELTA = "response.reasoning_summary_text.delta"
    const val OUTPUT_ITEM_ADDED = "response.output_item.added"
    const val OUTPUT_ITEM_DONE = "response.output_item.done"
    const val FUNCTION_CALL_ARGS_DELTA = "response.function_call_arguments.delta"
    const val FUNCTION_CALL_ARGS_DONE = "response.function_call_arguments.done"
    const val COMPLETED = "response.completed"
    const val INCOMPLETE = "response.incomplete"
    const val FAILED = "response.failed"
    /** Transport-layer error event: a peer of the three terminal events; carries its own code / message. */
    const val ERROR = "error"
    /** Same as above; the official SDK handles both spellings. */
    const val RESPONSE_ERROR = "response.error"
}

/** `type` values for Responses input/output items. */
object ResponsesItem {
    const val MESSAGE = "message"
    const val FUNCTION_CALL = "function_call"
    const val FUNCTION_CALL_OUTPUT = "function_call_output"
    const val REASONING = "reasoning"
    /** Image item produced by the model via the server-side image_generation tool; `result` is base64. */
    const val IMAGE_GENERATION_CALL = "image_generation_call"
}

/** `type` values for Responses content parts. */
object ResponsesPart {
    const val INPUT_TEXT = "input_text"
    const val OUTPUT_TEXT = "output_text"
    const val INPUT_IMAGE = "input_image"
    const val REASONING_TEXT = "reasoning_text"
    /** Element type of the reasoning item's `summary` array. */
    const val SUMMARY_TEXT = "summary_text"
    /** Part inside a message item when the model refuses; the text is in the `refusal` field rather than `text`. */
    const val REFUSAL = "refusal"
}
