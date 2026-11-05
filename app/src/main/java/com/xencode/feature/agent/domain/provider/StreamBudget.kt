package com.xencode.feature.agent.domain.provider

/**
 * Cap on incremental characters allowed to accumulate per streaming response.
 *
 * The log buffer `rawSse` is already capped at 512K, but the **business-side** accumulators (body / reasoning / tool arguments) previously had no limit:
 * a provider's baseUrl is user-defined, so a broken or hijacked upstream can stream deltas forever, and blindly accepting them would blow up the heap.
 */
const val MAX_STREAM_CHARS = 4 * 1024 * 1024

/**
 * Counts incremental characters accumulated in one streaming response; throws [StreamApiException] past [maxChars] to abort the request.
 *
 * Aborting rather than silently truncating: truncated tool arguments become half-finished JSON that gets executed as-is, and a truncated body hands the user a broken answer —
 * both are worse than a clean error. 4M chars is two orders of magnitude above any model's normal output, so normal use never triggers it.
 */
class StreamBudget(private val maxChars: Int = MAX_STREAM_CHARS) {
    private var total = 0L

    fun add(delta: String) {
        if (delta.isEmpty()) return
        total += delta.length
        if (total > maxChars) {
            throw StreamApiException(
                code = "response_too_large",
                message = "Upstream returned abnormal content (single response over ${maxChars / 1024}KB); aborted"
            )
        }
    }
}
