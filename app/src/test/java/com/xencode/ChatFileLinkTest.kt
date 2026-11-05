package com.xencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "File path vs URL" classification and line-number splitting for Markdown link targets in the chat area ([asChatFilePath] / [splitPathAndLine]).
 *
 * `file://` is not covered: its decoding goes through android.net.Uri, which is stubbed to default values in JVM unit tests.
 */
class ChatFileLinkTest {

    // ---------- asChatFilePath: classified as file path ----------

    @Test
    fun bare_file_name_with_line_is_file_path() {
        assertEquals("example.py:1", asChatFilePath("example.py:1"))
        assertEquals("config.json:1", asChatFilePath("config.json:1"))
        assertEquals("notes.md:12", asChatFilePath("notes.md:12"))
    }

    @Test
    fun relative_and_container_paths_are_file_paths() {
        assertEquals("notes.md", asChatFilePath("notes.md"))
        assertEquals("app/src/main/java/com/xencode/MainActivity.kt:42", asChatFilePath("app/src/main/java/com/xencode/MainActivity.kt:42"))
        assertEquals("~/workspace/example.py:1", asChatFilePath("~/workspace/example.py:1"))
        assertEquals("/etc/apk/repositories", asChatFilePath("/etc/apk/repositories"))
    }

    // ---------- asChatFilePath: classified as URL ----------

    @Test
    fun web_urls_go_to_browser() {
        assertNull(asChatFilePath("https://github.com/a/b"))
        assertNull(asChatFilePath("http://example.com:8080/p"))
        assertNull(asChatFilePath("content://com.xencode.files/x"))
    }

    @Test
    fun opaque_url_schemes_go_to_browser() {
        assertNull(asChatFilePath("mailto:dev@example.com"))
        assertNull(asChatFilePath("tel:12345"))
        assertNull(asChatFilePath("MAILTO:dev@example.com"))
    }

    // ---------- splitPathAndLine ----------

    @Test
    fun trailing_line_number_is_split_off() {
        assertEquals("example.py" to 1, splitPathAndLine("example.py:1"))
        assertEquals("~/workspace/a.kt" to 42, splitPathAndLine("~/workspace/a.kt:42"))
    }

    @Test
    fun path_without_line_number_keeps_zero() {
        assertEquals("notes.md" to 0, splitPathAndLine("notes.md"))
        assertEquals("~/workspace/notes.md" to 0, splitPathAndLine("~/workspace/notes.md"))
    }

    @Test
    fun oversized_line_number_falls_back_to_zero() {
        assertEquals("a.kt" to 0, splitPathAndLine("a.kt:99999999999999"))
    }
}
