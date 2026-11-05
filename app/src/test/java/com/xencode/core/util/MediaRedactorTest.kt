package com.xencode.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Log media redaction ([MediaRedactor]): oversized base64 (images etc.) must be replaced with an omission marker before hitting disk,
 * otherwise multi-MB single-line image data would blow up the session/per-day log files.
 */
class MediaRedactorTest {

    private fun b64(char: Char, count: Int): String = char.toString().repeat(count)

    @Test
    fun data_url_image_is_redacted() {
        val input = "see image data:image/png;base64,${b64('A', 1000)} end"
        val out = MediaRedactor.redact(input)
        assertTrue(out.contains("[base64 omitted: 1000 chars]"))
        assertTrue(out.startsWith("see image data:"))
        assertTrue(out.endsWith("end"))
    }

    @Test
    fun data_field_with_long_base64_is_redacted_pretty_and_compact() {
        val value = b64('B', 600)
        // Gson pretty printing (with spaces)
        val pretty = MediaRedactor.redact("""{"type":"image","mime_type":"image/png","data": "$value"}""")
        assertTrue(pretty.contains("\"data\": \"[base64 omitted: 600 chars]\""))
        assertTrue(!pretty.contains(value))
        // SSE compact format (no spaces)
        val compact = MediaRedactor.redact("""{"type":"image","data":"$value"}""")
        assertTrue(compact.contains("[base64 omitted: 600 chars]"))
    }

    @Test
    fun base64_data_key_is_redacted() {
        val value = b64('C', 500)
        val out = MediaRedactor.redact("""{"base64Data": "$value"}""")
        assertTrue(out.contains("\"base64Data\": \"[base64 omitted: 500 chars]\""))
    }

    @Test
    fun long_base64_under_unknown_key_is_caught_by_fallback() {
        // Fallback regex: overly long base64 under any field name is caught too
        val value = b64('D', 2000)
        val out = MediaRedactor.redact("""{"weird_key": "$value"}""")
        assertTrue(out.contains("[base64 omitted: 2000 chars]"))
        assertTrue(!out.contains(value))
    }

    @Test
    fun short_base64_below_threshold_is_kept() {
        val value = b64('E', 100)
        val out = MediaRedactor.redact("""{"data": "$value"}""")
        assertTrue(out.contains(value))
        assertTrue(!out.contains("omitted"))
    }

    @Test
    fun normal_text_is_untouched() {
        val input = "Normal log: file read failed, check the path ~/workspace/app/src/Main.kt"
        assertEquals(input, MediaRedactor.redact(input))
    }

    @Test
    fun already_redacted_text_does_not_blow_up() {
        val out = MediaRedactor.redact("data: [base64 omitted: 1629780 chars]")
        assertEquals("data: [base64 omitted: 1629780 chars]", out)
    }
}
