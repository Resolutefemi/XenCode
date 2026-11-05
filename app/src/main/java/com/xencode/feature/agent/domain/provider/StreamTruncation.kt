package com.xencode.feature.agent.domain.provider

import com.xencode.core.util.FileLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.IOException

private const val TAG = "StreamTruncation"

/**
 * Degraded-delivery marker for when a streaming response "loses the connection before the end marker arrives".
 *
 * The value must be within [AIResponse.TRUNCATION_STOP_REASONS] (here `length`): then
 * [AIResponse.isTruncated] is true and the agent loop automatically issues a continuation request to finish the answer,
 * instead of failing the whole turn and making the user resend.
 *
 * Covered drop scenarios: direct EOF from the peer (proxy/CDN closing early), mobile network switch, unilateral disconnect by a relay.
 * End markers per protocol: Anthropic `message_stop`, OpenAI Chat `[DONE]`,
 * Responses API `response.completed`, Gemini Interactions' terminal status.
 */
internal const val STREAM_TRUNCATED_STOP_REASON = "length"

/**
 * Whether degraded delivery is worthwhile after a drop: only when the body is non-blank.
 *
 * With no body at all (the connection dies right after being established, or only reasoning/a tool name arrived), degradation is pointless; still throw [IOException]
 * and follow the existing retry / multi-key switch / error flow — resending then is both safe and likely to fetch the full answer.
 */
internal fun CharSequence.isSalvageableAfterTruncation(): Boolean = isNotBlank()

internal fun logStreamTruncated(provider: String, chars: Int, lines: Int) {
    FileLogger.w(
        TAG,
        "$provider stream dropped before its end marker: received $lines lines / $chars chars. " +
            "Falling back to truncated output (stopReason=$STREAM_TRUNCATED_STOP_REASON); the agent loop will continue writing automatically"
    )
}

/**
 * Whether a tool call's arguments look complete.
 *
 * Arguments are stitched together piece by piece, so a drop almost always lands mid-JSON — executing a tool (writing a file, running a command) with broken arguments
 * is far more dangerous than dropping the call. So on a drop, keep only calls that parse as a complete JSON object:
 * a half-finished JSON always fails to parse, while anything closed out by `step.stop` / `arguments.done` always succeeds.
 */
internal fun looksLikeCompleteJsonObject(raw: String): Boolean {
    val text = raw.trim()
    if (!text.startsWith("{") || !text.endsWith("}")) return false
    return runCatching { Json.parseToJsonElement(text).jsonObject }.isSuccess
}
