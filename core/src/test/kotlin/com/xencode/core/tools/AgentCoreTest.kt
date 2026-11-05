package com.xencode.core.tools

import com.xencode.core.provider.ChatClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Permissions, TodoStore, Compactor and the AgentSession tool loop (with a
 * scripted fake transport — no network).
 */
class AgentCoreTest {

    // ---- Permissions ----

    @Test
    fun permissions_modesAndRulePrecedence() {
        // deny beats allow; ask beats allow; allow beats mode default.
        val args = """{"command":"git push origin main"}"""
        assertTrue(Permissions.check(Permissions.Mode.DEFAULT, emptyList(), emptyList(), emptyList(), "run_command", args) is Permissions.Decision.Ask)
        assertTrue(Permissions.check(Permissions.Mode.YOLO, emptyList(), emptyList(), emptyList(), "run_command", args) is Permissions.Decision.Allow)
        assertTrue(
            Permissions.check(Permissions.Mode.DEFAULT, listOf("cmd:git push*"), emptyList(), emptyList(), "run_command", args) is Permissions.Decision.Allow
        )
        assertTrue(
            Permissions.check(Permissions.Mode.YOLO, emptyList(), listOf("cmd:git push*"), emptyList(), "run_command", args) is Permissions.Decision.Deny
        )
        assertTrue(
            Permissions.check(Permissions.Mode.DEFAULT, emptyList(), emptyList(), listOf("tool:run_command"), "run_command", args) is Permissions.Decision.Ask
        )
        // read-only denies writes but allows reads
        assertTrue(Permissions.check(Permissions.Mode.READ_ONLY, emptyList(), emptyList(), emptyList(), "write_file", """{"path":"a"}""") is Permissions.Decision.Deny)
        assertTrue(Permissions.check(Permissions.Mode.READ_ONLY, emptyList(), emptyList(), emptyList(), "read_file", """{"path":"a"}""") is Permissions.Decision.Allow)
        // a safe command in default mode just runs
        assertTrue(Permissions.check(Permissions.Mode.DEFAULT, emptyList(), emptyList(), emptyList(), "run_command", """{"command":"ls -la"}""") is Permissions.Decision.Allow)
    }

    @Test
    fun permissions_storeRoundTrip() {
        val ws = java.nio.file.Files.createTempDirectory("perm-test").toFile()
        val store = PermissionStore(ws)
        store.save(PermissionStore.Config(mode = "yolo", allow = listOf("cmd:git *"), deny = listOf("cmd:rm *")))
        assertEquals(Permissions.Mode.YOLO, store.mode())
        assertTrue(store.check("run_command", """{"command":"git status"}""") is Permissions.Decision.Allow)
        assertTrue(store.check("run_command", """{"command":"rm -rf /"}""") is Permissions.Decision.Deny)
        ws.deleteRecursively()
    }

    // ---- TodoStore ----

    @Test
    fun todoStore_addCompleteRender() {
        val ws = java.nio.file.Files.createTempDirectory("todo-test").toFile()
        val store = TodoStore(ws)
        assertEquals("(todo list is empty)", store.render())
        store.add("write tests", "high")
        store.add("ship it")
        assertTrue(store.render().contains("[ ] (high) write tests"))
        store.complete("write")
        assertTrue(store.render().contains("[x] (high) write tests"))
        store.clear()
        assertEquals("(todo list is empty)", store.render())
        ws.deleteRecursively()
    }

    // ---- Compactor ----

    @Test
    fun compactor_keepsHeadAndTailAndSummarizesMiddle() {
        fun msg(role: String, text: String) = ChatClient.ChatMessage(role = role, content = text)
        val history = buildList {
            add(msg("user", "first question"))
            for (i in 1..20) {
                add(msg("assistant", "padding reply $i " + "x".repeat(200)))
                add(msg("user", "padding ask $i"))
            }
            add(msg("user", "latest question"))
        }
        val plan = Compactor.plan(history, maxChars = 1000, keepLast = 4)
        assertTrue(plan.savedChars > 0)
        assertEquals("first question", plan.kept.first().content)
        assertEquals("latest question", plan.kept.last().content)
        assertTrue(plan.summaryMessage != null)
        assertTrue(plan.droppedCount > 0)
        // Under the threshold: no-op.
        val small = listOf(msg("user", "hi"), msg("assistant", "hello"))
        assertEquals(0, Compactor.plan(small, maxChars = 1000).savedChars)
    }

    // ---- AgentSession tool loop (fake transport) ----

    /** Scripted transport: first call asks for a tool, second call answers with text. */
    private class FakeTransport(private val script: List<List<ChatClient.ChatEvent>>) : com.xencode.core.provider.ChatTransport {
        var calls = 0
        val seenMessages = mutableListOf<List<ChatClient.ChatMessage>>()
        override fun streamChat(
            config: com.xencode.core.provider.ProviderConfig,
            messages: List<ChatClient.ChatMessage>,
            tools: List<ChatClient.ToolSpec>,
            systemPrompt: String?
        ) = kotlinx.coroutines.flow.flow {
            seenMessages.add(messages.toList()) // snapshot: the caller keeps mutating its list
            val step = script[minOf(calls, script.size - 1)]
            calls++
            step.forEach { emit(it) }
        }
    }

    @Test
    fun agentSession_executesToolsAndLoops() = runBlocking {
        val events1 = listOf(
            ChatClient.ChatEvent.ToolCall("c1", "run_command", """{"command":"echo hi"}""")
        )
        val events2 = listOf(ChatClient.ChatEvent.TextDelta("all done"))
        val transport = FakeTransport(listOf(events1, events2))
        val session = AgentSession(
            client = transport,
            config = com.xencode.core.provider.ProviderConfig(name = "t", kind = "openai", baseUrl = "http://x", apiKey = "k", model = "m"),
            tools = LocalTools(java.nio.file.Files.createTempDirectory("agent-test").toFile()),
            systemPrompt = "s"
        )
        val deltas = StringBuilder()
        val reply = session.send("do it", onDelta = { deltas.append(it) })
        assertEquals("all done", reply)
        // The tool round-trip must be in the history sent to the model.
        assertEquals(3, transport.seenMessages.last().size) // user + assistant(toolCalls) + tool result
        assertEquals("tool", transport.seenMessages.last().last().role)
        assertTrue(transport.seenMessages.last().last().content!!.contains("exit=0"))
        assertEquals(2, transport.calls)
    }

    @Test
    fun agentSession_stopsAtToolRoundLimit() = runBlocking {
        val loop = listOf(listOf(ChatClient.ChatEvent.ToolCall("c1", "todo_read", "{}")))
        val transport = FakeTransport(loop)
        val session = AgentSession(
            client = transport,
            config = com.xencode.core.provider.ProviderConfig(name = "t", kind = "openai", baseUrl = "http://x", apiKey = "k", model = "m"),
            tools = LocalTools(java.nio.file.Files.createTempDirectory("agent-test2").toFile()),
            systemPrompt = "s",
            maxToolRounds = 2
        )
        val reply = session.send("loop forever")
        assertEquals(null, reply)
        assertTrue(session.lastFailure!!.contains("tool round limit"), session.lastFailure)
    }

    @Test
    fun agentSession_restoreAndCompactHistory() = runBlocking {
        val transport = FakeTransport(listOf(listOf(ChatClient.ChatEvent.TextDelta("ok"))))
        val session = AgentSession(
            client = transport,
            config = com.xencode.core.provider.ProviderConfig(name = "t", kind = "openai", baseUrl = "http://x", apiKey = "k", model = "m"),
            tools = LocalTools(java.nio.file.Files.createTempDirectory("agent-test3").toFile()),
            systemPrompt = "s"
        )
        val seeded = listOf(
            ChatClient.ChatMessage(role = "user", content = "old question"),
            ChatClient.ChatMessage(role = "assistant", content = "old answer")
        )
        session.restoreHistory(seeded)
        assertEquals(2, session.historySnapshot().size)
        assertEquals(0, session.compactHistory(maxChars = 100000)) // nothing to do
        assertEquals(2, session.historySnapshot().size)
    }
}
