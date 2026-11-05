package com.xencode.feature.agent.domain.container

/**
 * Hard output cap in characters for unbounded commands (git diff / file contents, etc.): exceeding it truncates and sets
 * [CommandResult.outputTruncated], preventing unbounded output from exhausting memory.
 */
const val MAX_UNBOUNDED_CHARS = 8 * 1024 * 1024

/**
 * Accumulates command output while bounding memory use: keeps the "first [headLimit] characters" and the "last [tailLimit] characters",
 * discarding the middle, so oversized output (e.g. `cat` on a large file / install logs) cannot blow up memory, model context, or UI rendering.
 *
 * Why keep both head+tail: the beginning usually carries the command intent and initial info while the end usually carries
 * errors and the final result — the most valuable parts for both model and user; the huge repetitive middle can be safely omitted, with the dropped character count noted at the omission point.
 *
 * Thread safety: append is called from coroutines concurrently reading stdout/stderr, hence method-level synchronization.
 */
class BoundedOutput(
    private val headLimit: Int = DEFAULT_HEAD,
    private val tailLimit: Int = DEFAULT_TAIL
) {
    private val head = StringBuilder()
    // Sliding window keeping only the most recent tailLimit characters (drops from the front when exceeded).
    private val tail = StringBuilder()
    private var total = 0L

    /** Total raw characters received (before truncation). */
    val totalChars: Long
        @Synchronized get() = total

    /** Whether middle truncation occurred (received amount exceeded the head+tail capacity). */
    val truncated: Boolean
        @Synchronized get() = total > head.length.toLong() + tail.length.toLong()

    @Synchronized
    fun append(text: String) {
        if (text.isEmpty()) return
        total += text.length
        if (head.length < headLimit) {
            val room = headLimit - head.length
            if (text.length <= room) {
                head.append(text)
                return
            }
            // Fill the head first; the remaining overflow goes into the tail window.
            head.append(text, 0, room)
            appendTail(text, room, text.length)
        } else {
            appendTail(text, 0, text.length)
        }
    }

    private fun appendTail(text: String, start: Int, end: Int) {
        tail.append(text, start, end)
        if (tail.length > tailLimit) tail.delete(0, tail.length - tailLimit)
    }

    /** Assembles the final text: the full output when not truncated; head + omission notice + tail when truncated. */
    @Synchronized
    fun build(): String {
        if (!truncated) return head.toString() + tail.toString()
        val omitted = total - head.length - tail.length
        return buildString {
            append(head)
            if (tail.isEmpty()) {
                append("\n\n…[Output too long; omitted ")
                append(omitted)
                append(" characters]…")
            } else {
                append("\n\n…[Output too long; omitted ")
                append(omitted)
                append(" characters in the middle; keeping head and tail only]…\n\n")
                append(tail)
            }
        }
    }

    companion object {
        const val DEFAULT_HEAD = 20_000
        const val DEFAULT_TAIL = 20_000

        /**
         * Hard-capped mode keeping only the beginning, up to [maxChars] characters (tail capacity is 0).
         *
         * Unlike the default head+tail: the omission notice appears only at the **end**, because the caller (git diff / file contents)
         * parses output line by line and a notice inserted in the middle would break the parsing.
         */
        fun hardCapped(maxChars: Int): BoundedOutput = BoundedOutput(maxChars, 0)
    }
}
