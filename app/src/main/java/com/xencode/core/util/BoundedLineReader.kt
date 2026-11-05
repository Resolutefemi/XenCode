package com.xencode.core.util

import java.io.Closeable
import java.io.Reader

/**
 * Per-line output cap (characters). `BufferedReader.readLine()` builds the entire line in
 * memory when it hits an extremely long one (a command dumping binary/large files to stdout,
 * e.g. `cat`-ing an executable or a large file through `base64`), which OOMs outright on
 * device.
 * Past the cap the line is truncated and the rest discarded, keeping memory bounded.
 */
internal const val MAX_STREAM_LINE_CHARS = 64 * 1024

/** Note line appended after an over-long line is truncated. */
internal const val LINE_TRUNCATED_NOTE = "[Line too long; truncated]"

/** One line of output; [truncated] is true when the line exceeded [MAX_STREAM_LINE_CHARS] and was cut short. */
internal class StreamLine(val text: String, val truncated: Boolean)

/**
 * Reads [reader] line by line with the same line-splitting semantics as
 * [java.io.BufferedReader.readLine] (`\n` / `\r` / `\r\n`), but caps line length at
 * [maxChars]: once the cap is reached it stops accumulating and keeps consuming and
 * discarding the rest of the line, avoiding OOM on extremely long lines.
 */
internal class BoundedLineReader(
    private val reader: Reader,
    private val maxChars: Int = MAX_STREAM_LINE_CHARS
) : Closeable {
    private val buf = CharArray(8192)
    private var pos = 0
    private var len = 0
    private var skipLf = false

    override fun close() = reader.close()

    fun readLine(): StreamLine? {
        val sb = StringBuilder()
        var truncated = false
        var sawContent = false
        while (true) {
            if (pos >= len) {
                if (len == -1) return if (sawContent) StreamLine(sb.toString(), truncated) else null
                len = reader.read(buf)
                pos = 0
                continue
            }
            val c = buf[pos++]
            if (skipLf) {
                skipLf = false
                if (c == '\n') continue
            }
            when (c) {
                '\n' -> return StreamLine(sb.toString(), truncated)
                '\r' -> {
                    skipLf = true
                    return StreamLine(sb.toString(), truncated)
                }
                else -> {
                    sawContent = true
                    if (sb.length < maxChars) sb.append(c) else truncated = true
                }
            }
        }
    }
}

/**
 * Lazy line-by-line reading: materializes only the current line at a time (capped at
 * [MAX_STREAM_LINE_CHARS]), reads as far as it goes, and never pulls the whole file into
 * memory.
 *
 * Each iteration calls [open] to reopen the reader, so the sequence can be iterated
 * repeatedly; an iteration that is fully consumed or throws closes it automatically.
 */
internal fun boundedLines(open: () -> Reader): Sequence<String> = sequence {
    BoundedLineReader(open()).use { reader ->
        while (true) {
            val line = reader.readLine() ?: break
            yield(line.text)
        }
    }
}