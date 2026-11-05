package com.xencode.core.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.io.File

/** Session / decision / event / checkpoint stores (git-backed ones skip without git). */
class StoresTest {

    @TempDir
    lateinit var tmp: File

    // ---- SessionStore ----

    @Test
    fun sessionStore_saveListLoadLatestDelete() {
        val store = SessionStore(tmp)
        assertTrue(store.list().isEmpty())

        val msgs = listOf(
            com.xencode.core.provider.ChatClient.ChatMessage(role = "user", content = "hello"),
            com.xencode.core.provider.ChatClient.ChatMessage(
                role = "assistant",
                toolCalls = listOf(com.xencode.core.provider.ChatClient.ToolCallMsg("c1", "run_command", "{}"))
            ),
            com.xencode.core.provider.ChatClient.ChatMessage(role = "tool", content = "exit=0", toolCallId = "c1")
        )
        val saved = store.save("s1", "my title", "prov", "model-1", 100L, msgs)
        assertEquals(3, saved.messages.size)
        assertEquals(100L, saved.createdAt)
        assertEquals("my title", store.list().single().title)
        assertEquals("model-1", store.list().single().model)

        store.save("s2", "second", "prov", "model-1", 200L, msgs.take(1))
        assertEquals("second", store.latest()!!.title) // newest updatedAt first

        val loaded = store.load("s1")!!
        assertEquals(3, loaded.messages.size)
        assertEquals("c1", loaded.messages[1].toolCalls!!.single().id)
        assertEquals("tool", loaded.messages[2].role)

        // Update keeps the original createdAt and title fallback.
        store.save("s1", "", "prov", "model-2", 999L, msgs)
        assertEquals(100L, store.load("s1")!!.createdAt)
        assertEquals("my title", store.load("s1")!!.title)

        assertTrue(store.delete("s2"))
        assertNull(store.load("s2"))
        assertFalse(store.delete("../evil"))
        assertEquals(60, store.titleFrom("x".repeat(80)).length)
        assertEquals("short", store.titleFrom("short"))
    }

    // ---- DecisionStore ----

    @Test
    fun decisionStore_createResolveList() {
        val store = DecisionStore(tmp)
        val card = store.create("Approve rm -rf", "destructive", listOf("approve", "reject"))
        assertEquals(1, store.open().size)
        assertNotNull(store.resolve(card.id, "APPROVE"))
        assertEquals(0, store.open().size)
        assertEquals("approve", store.list().single().choice)
        assertNull(store.resolve(card.id, "reject")) // already resolved
        assertNull(store.resolve("bogus", "approve"))
    }

    // ---- XenEventLog ----

    @Test
    fun eventLog_emitLatestPretty() {
        val log = XenEventLog(tmp)
        assertEquals(0, log.count())
        log.emit(XenEventLog.Types.STATE_SYNC, "hello", "node" to "g1", "ok" to true)
        log.emit(XenEventLog.Types.NODE_COMPLETED, "done", mapOf("node" to "g2"))
        val lines = log.latest(10)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("STATE_SYNC"))
        val pretty = XenEventLog.pretty(lines[0])
        assertTrue(pretty.contains("STATE_SYNC"), pretty)
        assertTrue(pretty.contains("node=g1"), pretty)
        // invalid lines pass through unchanged
        assertEquals("garbage", XenEventLog.pretty("garbage"))
    }

    // ---- CheckpointStore (needs git) ----

    @Test
    fun checkpointStore_createListRestore() {
        assumeTrue(gitAvailable(), "git not available")
        val ws = File(tmp, "repo").also { it.mkdirs() }
        runGit(ws, "init", "-q")
        runGit(ws, "config", "user.email", "t@t")
        runGit(ws, "config", "user.name", "t")
        File(ws, "a.txt").writeText("v1")
        runGit(ws, "add", "-A")
        runGit(ws, "commit", "-qm", "init")

        val store = CheckpointStore(ws)
        assertTrue(store.isGitRepo())

        File(ws, "a.txt").writeText("v2")
        File(ws, ".xencode").mkdirs()
        File(ws, ".xencode/TODO.json").writeText("[]")
        val cp = store.create("node1", "risky edit")
        assertNotNull(cp, store.lastError)
        assertEquals(1, store.list().size)
        // .xencode content must not be staged into the checkpoint.
        val show = runGit(ws, "show", "--stat", "--name-only", cp!!.commit!!)
        assertFalse(show.second.contains("TODO.json"), show.second)

        File(ws, "a.txt").writeText("v3")
        assertNull(store.restore(cp.id))
        // restore goes back to the CHECKPOINT commit (which captured v2)
        assertEquals("v2", File(ws, "a.txt").readText())

        assertTrue(store.restore("bogus")!!.contains("no checkpoint"))
        assertTrue(store.diff(cp.id)!!.contains("a.txt") || store.diff(cp.id)!!.contains("no differences"))
    }

    private fun gitAvailable(): Boolean = runCatching {
        ProcessBuilder("git", "--version").start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
    }.getOrDefault(false)

    private fun runGit(dir: File, vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        return proc.exitValue() to out
    }
}
