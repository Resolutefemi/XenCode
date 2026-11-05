package com.xencode.core.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.io.File

/** Tool-surface tests: edit/glob/grep/read-range/delete/todo + sandbox. */
class LocalToolsTest {

    @TempDir
    lateinit var tmp: File

    private fun tools(): LocalTools {
        val ws = File(tmp, "ws").also { it.mkdirs() }
        return LocalTools(ws)
    }

    @Test
    fun editFile_replacesExactUniqueText() {
        val t = tools()
        val f = File(t.rootDir(), "a.txt").apply { writeText("alpha beta gamma") }
        assertEquals(
            "Replaced 1 occurrence(s) in a.txt",
            t.execute("edit_file", """{"path":"a.txt","old_str":"beta","new_str":"BETA"}""")
        )
        assertEquals("alpha BETA gamma", f.readText())
    }

    @Test
    fun editFile_rejectsAmbiguousAndMissing() {
        val t = tools()
        File(t.rootDir(), "b.txt").writeText("x x x")
        assertTrue(t.execute("edit_file", """{"path":"b.txt","old_str":"x","new_str":"y"}""").startsWith("ERROR: old_str occurs 3 times"))
        assertEquals(
            "Replaced 3 occurrence(s) in b.txt",
            t.execute("edit_file", """{"path":"b.txt","old_str":"x","new_str":"y","replace_all":true}""")
        )
        assertTrue(t.execute("edit_file", """{"path":"b.txt","old_str":"zzz","new_str":"y"}""").startsWith("ERROR: old_str not found"))
    }

    @Test
    fun editFile_createsWithEmptyOldStr() {
        val t = tools()
        t.execute("edit_file", """{"path":"new/dir/c.txt","old_str":"","new_str":"content here"}""")
        assertEquals("content here", File(t.rootDir(), "new/dir/c.txt").readText())
    }

    @Test
    fun glob_matchesStarStar() {
        val t = tools()
        File(t.rootDir(), "src/deep").mkdirs()
        File(t.rootDir(), "src/A.kt").writeText("")
        File(t.rootDir(), "src/deep/B.kt").writeText("")
        File(t.rootDir(), "src/deep/C.md").writeText("")
        val out = t.execute("glob", """{"pattern":"**/*.kt"}""")
        assertTrue(out.contains("src/A.kt"), out)
        assertTrue(out.contains("src/deep/B.kt"), out)
        assertFalse(out.contains("C.md"), out)
    }

    @Test
    fun grep_contentCountAndFilesModes() {
        val t = tools()
        File(t.rootDir(), "one.txt").writeText("hello world\nsecond line\nhello again")
        File(t.rootDir(), "two.txt").writeText("nothing here")
        File(t.rootDir(), "sub").mkdirs()
        File(t.rootDir(), "sub/three.txt").writeText("say hello")

        val content = t.execute("grep", """{"pattern":"hello"}""")
        assertTrue(content.contains("one.txt:1:hello world"), content)
        assertTrue(content.contains("one.txt:3:hello again"), content)
        assertTrue(content.contains("sub/three.txt:1:say hello"), content)

        val count = t.execute("grep", """{"pattern":"hello","output_mode":"count"}""")
        assertTrue(count.contains("one.txt:2"), count)
        assertTrue(count.contains("sub/three.txt:1"), count)

        val files = t.execute("grep", """{"pattern":"hello","output_mode":"files_with_matches"}""")
        assertTrue(files.contains("one.txt") && files.contains("sub/three.txt"), files)
        assertFalse(files.contains("two.txt"), files)

        val globFiltered = t.execute("grep", """{"pattern":"hello","glob":"sub/*.txt","output_mode":"files_with_matches"}""")
        assertTrue(globFiltered.contains("three.txt"), globFiltered)
        assertFalse(globFiltered.contains("one.txt"), globFiltered)

        assertTrue(t.execute("grep", """{"pattern":"zzzz"}""").startsWith("(no matches"))
        assertTrue(t.execute("grep", """{"pattern":"(unclosed"}""").startsWith("ERROR: bad regex"))
    }

    @Test
    fun readFile_offsetAndLimit() {
        val t = tools()
        File(t.rootDir(), "lines.txt").writeText((1..50).joinToString("\n") { "line$it" })
        val out = t.execute("read_file", """{"path":"lines.txt","offset":10,"limit":3}""")
        assertEquals("line10\nline11\nline12", out)
    }

    @Test
    fun deleteFile_removesFileAndEmptyDir() {
        val t = tools()
        File(t.rootDir(), "d").mkdirs()
        File(t.rootDir(), "d/x.txt").writeText("x")
        assertEquals("Deleted d/x.txt", t.execute("delete_file", """{"path":"d/x.txt"}"""))
        assertEquals("Deleted empty directory d", t.execute("delete_file", """{"path":"d"}"""))
        assertTrue(t.execute("delete_file", """{"path":"nope"}""").startsWith("ERROR"))
    }

    @Test
    fun runCommand_honorsTimeoutParamAndReportsExitCode() {
        val t = tools()
        val out = t.execute("run_command", """{"command":"echo marker","timeout_sec":5}""")
        assertTrue(out.contains("exit=0"), out)
        assertTrue(out.contains("marker"), out)
    }

    @Test
    fun todoWriteAndRead_shareTheWorkspaceStore() {
        val t = tools()
        val write = t.execute(
            "todo_write",
            """{"todos":[{"content":"first","status":"pending","priority":"high"},{"content":"second","status":"completed"}]}"""
        )
        assertTrue(write.contains("TODO list updated"), write)
        val read = t.execute("todo_read", "{}")
        assertTrue(read.contains("[ ] (high) first"), read)
        assertTrue(read.contains("[x] (medium) second"), read)
        // The store file is where the user-facing `xencode todo` reads it.
        assertTrue(File(t.rootDir(), ".xencode/TODO.json").isFile)
    }

    @Test
    fun sandbox_rejectsEscapingPaths() {
        val t = tools()
        assertTrue(t.execute("read_file", """{"path":"../../etc/passwd"}""").startsWith("ERROR"))
        assertTrue(t.execute("write_file", """{"path":"/abs/path","content":"x"}""").startsWith("ERROR"))
    }
}
