package com.xencode.feature.agent.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies pre-persistence content sanitization and byte-based length caps: stripping inline base64 image data URLs and truncating oversized content by UTF-8 bytes,
 * keeping huge fields from blowing up a row and crashing SQLite's CursorWindow.
 */
class MessagePersistenceUseCaseTest {

    @Test
    fun sanitizeContent_stripsInlineBase64Image() {
        val input = "Here is the reply. See the image: ![screenshot](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==) End."

        val result = MessagePersistenceUseCase.sanitizeContent(input)

        assertTrue(result.contains("Image omitted"))
        assertFalse(result.contains("iVBORw0KGgo"))
        assertTrue(result.contains("Here is the reply"))
    }

    @Test
    fun sanitizeContent_stripsBareDataUrl() {
        val input = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQ=="

        val result = MessagePersistenceUseCase.sanitizeContent(input)

        assertTrue(result.contains("Image omitted"))
        assertFalse(result.contains("/9j/4AAQSk"))
    }

    @Test
    fun sanitizeContent_truncatesOversizedPlainText() {
        val oversized = "a".repeat(300_000)

        val result = MessagePersistenceUseCase.sanitizeContent(oversized)

        assertTrue(result.endsWith(MessagePersistenceUseCase.CONTENT_TRUNCATED_MARKER))
        val kept = result.removeSuffix(MessagePersistenceUseCase.CONTENT_TRUNCATED_MARKER)
        assertTrue(kept.toByteArray(Charsets.UTF_8).size <= MessagePersistenceUseCase.MAX_CONTENT_BYTES)
    }

    @Test
    fun sanitizeContent_boundsBytesForMultibyteText() {
        // A single CJK character is 3 bytes: a character-count cap cannot bound the real footprint; truncation must go by bytes.
        val oversized = "汉".repeat(100_000)

        val result = MessagePersistenceUseCase.sanitizeContent(oversized)

        assertTrue(result.endsWith(MessagePersistenceUseCase.CONTENT_TRUNCATED_MARKER))
        val kept = result.removeSuffix(MessagePersistenceUseCase.CONTENT_TRUNCATED_MARKER)
        assertTrue(kept.toByteArray(Charsets.UTF_8).size <= MessagePersistenceUseCase.MAX_CONTENT_BYTES)
    }

    @Test
    fun sanitizeContent_keepsNormalTextUnchanged() {
        val normal = "ordinary message, nothing to handle.".repeat(10)

        assertEquals(normal, MessagePersistenceUseCase.sanitizeContent(normal))
    }

    @Test
    fun capBytes_truncatesOversizedJson() {
        val oversized = "[{\"id\":\"" + "a".repeat(300_000) + "\"}]"

        val result = MessagePersistenceUseCase.capBytes(
            oversized,
            MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES
        )

        assertTrue(result.toByteArray(Charsets.UTF_8).size <= MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES)
    }

    @Test
    fun capBytes_doesNotSplitSurrogatePair() {
        val oversized = "😀".repeat(50_000)

        val result = MessagePersistenceUseCase.capBytes(
            oversized,
            MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES
        )

        assertTrue(result.toByteArray(Charsets.UTF_8).size <= MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES)
        assertEquals(0, result.length % 2)
    }

    @Test
    fun capBytes_keepsNormalJsonUnchanged() {
        val normal = "[{\"id\":\"call_1\",\"name\":\"writeFile\"}]"

        assertEquals(
            normal,
            MessagePersistenceUseCase.capBytes(normal, MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES)
        )
    }
}
