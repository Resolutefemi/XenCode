package com.xencode.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Log media redaction ([AILogger]): image base64 in request/response bodies must be replaced with a placeholder before hitting disk,
 * especially for nested data URLs like OpenAI's `image_url.url` — the old whole-text regex missed those.
 */
class AILoggerRedactTest {

    private fun bigBase64(chars: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        return buildString(chars) { repeat(chars) { append(alphabet[it % alphabet.length]) } }
    }

    @Test
    fun redactsOpenAiImageUrlDataUrl() {
        val data = bigBase64(2000)
        val body = mapOf(
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf(
                                "url" to "data:image/jpeg;base64,$data",
                                "detail" to "auto"
                            )
                        )
                    )
                )
            )
        )

        val out = AILogger.redactToJson(body)

        assertFalse("embedded image base64 was not redacted", out.contains(data))
        assertTrue(out.contains("[base64 omitted:"))
        assertTrue(out.contains("\"detail\": \"auto\""))
    }

    @Test
    fun redactsAnthropicSourceData() {
        val data = bigBase64(2000)
        val body = mapOf(
            "source" to mapOf("type" to "base64", "media_type" to "image/png", "data" to data)
        )

        val out = AILogger.redactToJson(body)

        assertFalse("base64 in source.data was not redacted", out.contains(data))
        assertTrue(out.contains("[base64 omitted:"))
    }

    @Test
    fun redactsBase64DataField() {
        val data = bigBase64(2000)

        val out = AILogger.redactToJson(mapOf("base64Data" to data))

        assertFalse(out.contains(data))
        assertTrue(out.contains("[base64 omitted:"))
    }

    @Test
    fun keepsNormalTextIntact() {
        val out = AILogger.redactToJson(mapOf("text" to "hello world, this is normal text and must not be modified."))

        assertTrue(out.contains("hello world"))
        assertTrue(out.contains("normal text"))
        assertFalse(out.contains("base64 omitted"))
    }

    @Test
    fun keepsShortUrlIntact() {
        val out = AILogger.redactToJson(mapOf("url" to "https://example.com/a.png"))

        assertTrue(out.contains("https://example.com/a.png"))
        assertFalse(out.contains("base64 omitted"))
    }
}
