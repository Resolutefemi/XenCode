package com.xencode.desktop

import com.xencode.core.db.DbXpLedger
import com.xencode.core.db.SharedChatStore
import com.xencode.core.db.SharedDb
import com.xencode.core.goal.GoalDagResult
import com.xencode.core.goal.GoalStatus
import com.xencode.core.provider.ChatClient
import com.xencode.core.provider.ChatTransport
import com.xencode.core.provider.ProviderConfig
import kotlinx.coroutines.flow.flow
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop <-> shared-database wiring: every chat turn lands in
 * `aicode_agent_db` as a chat_sessions row, USER/ASSISTANT/TOOL rows in
 * agent_messages, one llm_call_records row per turn and the +5 XP turn award
 * in xp_events — the same tables the Android app writes through Room.
 * Everything runs against a temp database; `~/.xencode` is never touched.
 */
class SharedDbWiringTest {

    /** Streams one assistant reply with provider-reported usage. */
    private class TextTransport : ChatTransport {
        override fun streamChat(
            config: ProviderConfig,
            messages: List<ChatClient.ChatMessage>,
            tools: List<ChatClient.ToolSpec>,
            systemPrompt: String?
        ) = flow {
            emit(ChatClient.ChatEvent.TextDelta("db-wired reply"))
            emit(ChatClient.ChatEvent.Completed(88, 12))
        }
    }

    /** Requests one safe tool call (list_files), then finishes. */
    private class ToolTransport : ChatTransport {
        var round = 0
        override fun streamChat(
            config: ProviderConfig,
            messages: List<ChatClient.ChatMessage>,
            tools: List<ChatClient.ToolSpec>,
            systemPrompt: String?
        ) = flow {
            round++
            if (round == 1) {
                emit(ChatClient.ChatEvent.ToolCall("c1", "list_files", "{}"))
                emit(ChatClient.ChatEvent.Completed(10, 4))
            } else {
                emit(ChatClient.ChatEvent.TextDelta("listed"))
                emit(ChatClient.ChatEvent.Completed(20, 6))
            }
        }
    }

    private fun provider() = ProviderConfig(
        name = "deepseek", kind = "openai", baseUrl = "https://example.com", apiKey = "k", model = "deepseek-chat"
    )

    private fun waitUntil(timeoutMillis: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMillis}ms" }
            Thread.sleep(50)
        }
    }

    @Test
    fun chatTurnPersistsSessionMessagesTokensAndXp() {
        val ws = createTempDirectory(prefix = "xencode-dbws")
        val home = createTempDirectory(prefix = "xencode-dbhome")
        try {
            val db = SharedDb.open(home.resolve("aicode_agent_db").toString())
            val controller = DesktopAgentController(provider(), ws.toString(), db = db, client = TextTransport())

            controller.send("wire me to the db")
            waitUntil { !controller.busy.value && controller.messages.value.size >= 2 }

            val store = SharedChatStore(db)
            val sessions = store.sessionsForWorkspace(ws.toString())
            assertEquals(1, sessions.size, "one chat_sessions row expected")
            val s = sessions.single()
            assertEquals("wire me to the db", s.title)
            assertEquals("deepseek", s.providerId)
            assertEquals("deepseek-chat", s.model)
            // Usage from the provider's Completed event flowed into the session totals.
            assertEquals(88, s.totalInputTokens)
            assertEquals(12, s.totalOutputTokens)

            val rows = store.messagesFor(s.id)
            assertEquals(listOf("USER", "ASSISTANT"), rows.map { it.role })
            assertEquals("wire me to the db", rows[0].content)
            assertEquals("db-wired reply", rows[1].content)
            assertEquals(88, rows[1].inputTokens)
            assertEquals(12, rows[1].outputTokens)

            val totals = store.llmTotals()
            assertEquals(1, totals.calls)
            assertEquals(88L, totals.inputTokens)
            assertEquals(0, totals.errors)

            val xp = DbXpLedger(db).summary()
            assertEquals(5L, xp.totalXp)
            assertEquals(1, xp.recent.count { it.kind == "TURN_COMPLETED" })
        } finally {
            ws.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun toolCallsPersistAsToolRowsWithStatus() {
        val ws = createTempDirectory(prefix = "xencode-dbws2")
        val home = createTempDirectory(prefix = "xencode-dbhome2")
        try {
            val db = SharedDb.open(home.resolve("aicode_agent_db").toString())
            val controller = DesktopAgentController(provider(), ws.toString(), db = db, client = ToolTransport())

            controller.send("list the workspace")
            waitUntil { !controller.busy.value && controller.messages.value.size >= 3 }

            val store = SharedChatStore(db)
            val s = store.sessionsForWorkspace(ws.toString()).single()
            val rows = store.messagesFor(s.id)
            assertEquals(listOf("USER", "TOOL", "ASSISTANT"), rows.map { it.role })
            assertEquals("list_files", rows[1].toolName)
            assertEquals(false, rows[1].isError)
            // Usage was summed across both provider rounds of the turn.
            assertEquals(30, s.totalInputTokens)
            assertEquals(10, s.totalOutputTokens)
        } finally {
            ws.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun goalCompletionAwardsXpIntoTheSharedDatabase() {
        val ws = createTempDirectory(prefix = "xencode-dbgoal")
        val home = createTempDirectory(prefix = "xencode-dbgoalhome")
        try {
            val db = SharedDb.open(home.resolve("aicode_agent_db").toString())
            val board = GoalBoard(ws.toString(), DbXpLedger(db))
            assertTrue(board.add("Ship the DB build", emptyList(), "") is GoalDagResult.Ok)
            val goal = board.dag.nodes.single()
            assertTrue(board.complete(goal.id) is GoalDagResult.Ok)
            assertEquals(GoalStatus.DONE, board.dag.nodes.single().status)

            val s = DbXpLedger(db).summary()
            assertEquals(50L, s.totalXp)
            assertEquals(1, s.recent.count { it.kind == "GOAL_COMPLETED" })
        } finally {
            ws.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedTurnRecordsAnErroredLlmCallAndNoXp() {
        val ws = createTempDirectory(prefix = "xencode-dbws3")
        val home = createTempDirectory(prefix = "xencode-dbhome3")
        try {
            val db = SharedDb.open(home.resolve("aicode_agent_db").toString())
            val failing = object : ChatTransport {
                override fun streamChat(
                    config: ProviderConfig,
                    messages: List<ChatClient.ChatMessage>,
                    tools: List<ChatClient.ToolSpec>,
                    systemPrompt: String?
                ) = flow { emit(ChatClient.ChatEvent.Failed("provider down")) }
            }
            val controller = DesktopAgentController(provider(), ws.toString(), db = db, client = failing)
            controller.send("this will fail")
            waitUntil { !controller.busy.value && controller.messages.value.isNotEmpty() }

            val store = SharedChatStore(db)
            val totals = store.llmTotals()
            assertEquals(1, totals.calls)
            assertEquals(1, totals.errors)
            assertEquals(0L, DbXpLedger(db).summary().totalXp)
            // The user row is still kept (durable transcript).
            val s = store.sessionsForWorkspace(ws.toString()).single()
            assertEquals(listOf("USER"), store.messagesFor(s.id).map { it.role })
        } finally {
            ws.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }
}
