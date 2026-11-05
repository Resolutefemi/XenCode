package com.xencode.feature.agent.domain.tool.explorer

import com.xencode.feature.agent.domain.tool.ToolResult
import com.xencode.feature.workspace.domain.FileAccessProvider
import com.xencode.feature.workspace.domain.FileEntry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Output truncation and pipe allowlist for the list tool's `| head [-n N]`.
 *
 * list does not go through a shell (it walks the host file system directly); `| head` is semantically equivalent to "output only the first N lines",
 * but the security validation shares the same [headLinesOf] allowlist as search — non-head pipes are always rejected.
 */
class ListFilesToolTest {

    private val dir = "/root/workspace/demo"
    private val files = listOf(
        FileEntry("a.txt", isDirectory = false, size = 10, lastModified = 1000),
        FileEntry("b.txt", isDirectory = false, size = 20, lastModified = 2000),
        FileEntry("c.txt", isDirectory = false, size = 30, lastModified = 3000),
        FileEntry(".hidden", isDirectory = false, size = 5, lastModified = 500)
    )

    private val tool = ListFilesTool(FakeFileAccess())

    private inner class FakeFileAccess : FileAccessProvider {
        override fun exists(path: String): Boolean = path == dir
        override fun isDirectory(path: String): Boolean = path == dir
        override fun isFile(path: String): Boolean = path != dir
        override fun listFiles(path: String): List<FileEntry> = if (path == dir) files else emptyList()
        override fun parentPath(path: String): String? = "/root/workspace"
        override fun toDisplayPath(path: String): String = path

        override fun readFile(path: String): String = throw UnsupportedOperationException()
        override fun readLines(path: String): Sequence<String> = throw UnsupportedOperationException()
        override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) = throw UnsupportedOperationException()
        override fun fileSize(path: String): Long = throw UnsupportedOperationException()
        override fun lastModified(path: String): Long = throw UnsupportedOperationException()
        override fun permissions(path: String): String = throw UnsupportedOperationException()
        override fun readBytes(path: String): ByteArray = throw UnsupportedOperationException()
        override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) = throw UnsupportedOperationException()
        override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long = throw UnsupportedOperationException()
        override fun listFilesRecursive(path: String, maxDepth: Int): List<String> = emptyList()
        override fun copyToLocal(path: String): File = throw UnsupportedOperationException()
        override fun delete(path: String) = throw UnsupportedOperationException()
        override fun mkdirs(path: String) = throw UnsupportedOperationException()
        override fun deleteRecursively(path: String) = throw UnsupportedOperationException()
        override fun rename(path: String, newPath: String) = throw UnsupportedOperationException()
        override fun copy(path: String, newPath: String, overwrite: Boolean) = throw UnsupportedOperationException()
        override fun move(path: String, newPath: String, overwrite: Boolean) = throw UnsupportedOperationException()
    }

    private suspend fun runList(args: String): ToolResult {
        return tool.execute(mapOf("args" to JsonPrimitive(args)))
    }

    private fun contentOf(result: ToolResult): String {
        val success = result as ToolResult.Success
        return (success.data as JsonObject)["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private fun entriesOf(result: ToolResult): Int {
        val success = result as ToolResult.Success
        return (success.data as JsonObject)["entries"]?.jsonPrimitive?.contentOrNull?.toInt() ?: -1
    }

    private fun truncatedOf(result: ToolResult): Boolean {
        val success = result as ToolResult.Success
        return (success.data as JsonObject)["truncated"]?.jsonPrimitive?.contentOrNull == "true"
    }

    @Test
    fun list_noPipe_listsAllEntries() = runTest {
        val result = runList("-la $dir")
        assertTrue(result is ToolResult.Success)
        assertEquals(6, entriesOf(result)) // . .. a.txt b.txt c.txt .hidden
        assertFalse(truncatedOf(result))
        assertTrue(contentOf(result).contains("a.txt"))
        assertTrue(contentOf(result).contains(".hidden"))
    }

    @Test
    fun list_headLimit_truncatesOutput() = runTest {
        val result = runList("-la $dir | head -2")
        assertTrue(result is ToolResult.Success)
        assertEquals(2, entriesOf(result))
        assertTrue(truncatedOf(result))
        val content = contentOf(result)
        // A trailing newline adds an empty element to lines(); trimEnd before counting lines
        assertTrue(content.trimEnd('\n').lines().size == 2)
        // head truncation is user-requested; no system "limit reached" notice should be appended
        assertFalse(content.contains("reached the"))
    }

    @Test
    fun list_bareHead_defaultsTo10Lines() = runTest {
        val result = runList("-la $dir | head")
        assertTrue(result is ToolResult.Success)
        assertEquals(6, entriesOf(result))
        assertFalse(truncatedOf(result))
    }

    @Test
    fun list_headZero_outputsNothing() = runTest {
        val result = runList("-la $dir | head -0")
        assertTrue(result is ToolResult.Success)
        assertEquals(0, entriesOf(result))
        assertTrue(truncatedOf(result))
    }

    @Test
    fun list_multipleHeadSegments_takeMinimum() = runTest {
        val result = runList("-la $dir | head -2 | head -1")
        assertTrue(result is ToolResult.Success)
        assertEquals(1, entriesOf(result))
        assertTrue(truncatedOf(result))
    }

    @Test
    fun list_arbitraryPipeCommand_isRejected() = runTest {
        val result = runList("-la $dir | rm -rf /")
        val error = result as ToolResult.Error
        assertEquals("INVALID_PIPE", error.code)
    }

    @Test
    fun list_pipeWithoutLeadingArgs_isRejected() = runTest {
        val result = runList("| head -2")
        val error = result as ToolResult.Error
        assertEquals("INVALID_PIPE", error.code)
    }
}
