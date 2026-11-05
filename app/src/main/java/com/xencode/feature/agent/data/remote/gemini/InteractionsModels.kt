package com.xencode.feature.agent.data.remote.gemini

/**
 * Step `type` values for the Interactions API (`v1beta/interactions`).
 *
 * Unlike generateContent's `contents[].parts[]`: a conversation turn is split into independent steps on a timeline,
 * with tool calls, thinking, and model output each occupying one step; in stateless replay the whole history is just the step array.
 */
object InteractionStep {
    /** User input; the body is in `content` (a Content array; a plain string is also accepted). */
    const val USER_INPUT = "user_input"

    /** Model output; the body is in `content` (a Content array). */
    const val MODEL_OUTPUT = "model_output"

    /**
     * Thinking step: `signature` is a signature for backend verification, `summary` is an optional thinking summary (a Content array).
     * In stateless mode these must be sent back verbatim, otherwise the previous turn's reasoning context cannot be continued.
     */
    const val THOUGHT = "thought"

    /** The model requests a function call: `id` / `name` / `arguments` (an object, not a string). */
    const val FUNCTION_CALL = "function_call"

    /** Function execution result: `call_id` matches the call's `id`; `result` is a Content array / object / string. */
    const val FUNCTION_RESULT = "function_result"
}

/** Content block `type` values for Interactions. */
object InteractionContent {
    const val TEXT = "text"
    const val IMAGE = "image"
}

/**
 * Interactions streaming SSE event types; the discriminator field is **`event_type`** (not `type`).
 *
 * Lifecycle events carry an `interaction` object (with status / usage); step events are grouped by `index`.
 * The stream **does not send `[DONE]`**; the only way to detect the end is whether interaction status has reached a terminal state (see [TERMINAL_STATUSES]).
 */
object InteractionEvent {
    const val STEP_START = "step.start"
    const val STEP_DELTA = "step.delta"
    const val STEP_STOP = "step.stop"

    /** Transport-layer / platform error; carries its own `error: {code, message}`. */
    const val ERROR = "error"

    /** Lifecycle event prefix: created / in_progress / status_update / requires_action / completed, etc. */
    const val LIFECYCLE_PREFIX = "interaction."
}

/** Values of `delta.type` inside `step.delta`. */
object InteractionDelta {
    /** Body text delta; the text is in `text`. */
    const val TEXT = "text"

    /** Thinking summary delta: the text is in `content.text` (some implementations flatten it into `text`). */
    const val THOUGHT_SUMMARY = "thought_summary"

    /** An alternate spelling of the above; the migration guide examples use this one. */
    const val THOUGHT = "thought"

    /** Thinking signature; the value is in `signature`, attached to the thought step it belongs to. */
    const val THOUGHT_SIGNATURE = "thought_signature"

    /** Verbatim incremental tool arguments; the fragment is in `arguments` (the migration guide calls it `partial_arguments`). */
    const val ARGUMENTS = "arguments_delta"

    /** An alternate spelling of the above. */
    const val ARGUMENTS_LEGACY = "arguments"
}

/**
 * Terminal states of an interaction. In streaming, the stream can be closed once any terminal state is read:
 * when tools are pending the server stops at `requires_action` and **does not** send `completed` afterwards,
 * so waiting only for `completed` would read to the end of the stream and be misjudged as a dropped connection.
 */
val TERMINAL_STATUSES = setOf(
    "completed",
    "requires_action",
    "incomplete",
    "failed",
    "cancelled",
    "budget_exceeded"
)
