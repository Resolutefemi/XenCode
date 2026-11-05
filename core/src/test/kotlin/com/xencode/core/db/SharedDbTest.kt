package com.xencode.core.db

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared-database contract: the PC line writes the exact schema the
 * Android app persists through Room (`aicode_agent_db`), so rows are
 * copy-compatible with `ChatSessionEntity` / `AgentMessageEntity` /
 * `XpEventEntity` / `LlmCallRecordEntity`.
 */
class SharedDbTest {

    // ---- Schema -------------------------------------------------------------

    @Test
    fun schemaCreatesAllSharedTables() {
        SharedDb.inMemory().use { db ->
            val tables = db.withConnection { c ->
                c.createStatement().executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
                ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
            for (t in listOf("chat_sessions", "agent_messages", "xp_events", "llm_call_records")) {
                assertTrue(t in tables, "missing table $t in $tables")
            }
        }
    }

    @Test
    fun fileDatabaseIsCreatedAtTheGivenPath() {
        val home = createTempDirectory(prefix = "xencode-db")
        try {
            val path = home.resolve("nested/aicode_agent_db").toString()
            SharedDb.open(path).use { db ->
                assertEquals(path, db.file?.path)
                assertTrue(java.io.File(path).isFile)
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    // ---- Chat store -----------------------------------------------------------

    @Test
    fun sessionUpsertPreservesAccumulatedTokens() {
        SharedDb.inMemory().use { db ->
            val store = SharedChatStore(db)
            assertTrue(
                store.upsertSession(
                    SharedChatStore.SessionRow(
                        id = "s1", title = "first", createdAt = 1000, updatedAt = 1000,
                        workspacePath = "/ws", providerId = "deepseek", model = "deepseek-chat"
                    )
                )
            )
            store.addSessionTokens("s1", inputDelta = 120, outputDelta = 40, lastInput = 120)
            // Re-save the session (title/model update) — token totals survive.
            assertTrue(
                store.upsertSession(
                    SharedChatStore.SessionRow(
                        id = "s1", title = "renamed", createdAt = 1000, updatedAt = 2000,
                        workspacePath = "/ws", providerId = "deepseek", model = "deepseek-reasoner"
                    )
                )
            )
            val row = store.sessionRow("s1")!!
            assertEquals("renamed", row.title)
            assertEquals("deepseek-reasoner", row.model)
            assertEquals(120, row.totalInputTokens)
            assertEquals(40, row.totalOutputTokens)
            assertEquals(120, row.lastInputTokens)
            assertNull(store.sessionRow("missing"))
        }
    }

    @Test
    fun messagesRoundTripInRoomRoleVocabulary() {
        SharedDb.inMemory().use { db ->
            val store = SharedChatStore(db)
            store.upsertSession(SharedChatStore.SessionRow("s1", "t", 1, 1, "/ws"))
            val now = System.currentTimeMillis()
            assertTrue(
                store.appendMessage(
                    SharedChatStore.MessageRow(
                        id = store.newMessageId(now), sessionId = "s1", role = "USER",
                        content = "fix the build", timestamp = now
                    )
                )
            )
            assertTrue(
                store.appendMessage(
                    SharedChatStore.MessageRow(
                        id = store.newMessageId(now + 1), sessionId = "s1", role = "TOOL",
                        content = "exit=0", timestamp = now + 1, toolName = "run_command",
                        toolArgs = """{"command":"ls"}""", isError = false
                    )
                )
            )
            assertTrue(
                store.appendMessage(
                    SharedChatStore.MessageRow(
                        id = store.newMessageId(now + 2), sessionId = "s1", role = "ASSISTANT",
                        content = "done", timestamp = now + 2, inputTokens = 90, outputTokens = 12
                    )
                )
            )
            val rows = store.messagesFor("s1")
            assertEquals(listOf("USER", "TOOL", "ASSISTANT"), rows.map { it.role })
            assertEquals("run_command", rows[1].toolName)
            assertEquals(false, rows[1].isError)
            assertEquals(12, rows[2].outputTokens)
            assertEquals(3, store.messageCount("s1"))
            assertTrue(store.messagesFor("nope").isEmpty())
        }
    }

    @Test
    fun llmCallRecordsAccumulate() {
        SharedDb.inMemory().use { db ->
            val store = SharedChatStore(db)
            val now = System.currentTimeMillis()
            assertTrue(store.insertLlmCall(SharedChatStore.LlmCallRow(sessionId = "s1", model = "m", inputTokens = 10, outputTokens = 5, durationMillis = 800, createdAt = now)) > 0)
            assertTrue(
                store.insertLlmCall(
                    SharedChatStore.LlmCallRow(
                        sessionId = "s1", model = "m", status = "error",
                        errorMessage = "HTTP 429", createdAt = now + 1
                    )
                ) > 0
            )
            val totals = store.llmTotals()
            assertEquals(2, totals.calls)
            assertEquals(10L, totals.inputTokens)
            assertEquals(5L, totals.outputTokens)
            assertEquals(1, totals.errors)
        }
    }

    @Test
    fun sessionsAreKeptApartByWorkspacePath() {
        SharedDb.inMemory().use { db ->
            val store = SharedChatStore(db)
            store.upsertSession(SharedChatStore.SessionRow("a", "A", 1, 10, "/ws-a"))
            store.upsertSession(SharedChatStore.SessionRow("b", "B", 1, 20, "/ws-b"))
            store.upsertSession(SharedChatStore.SessionRow("c", "C", 1, 30, "/ws-a"))
            assertEquals(listOf("c", "a"), store.sessionsForWorkspace("/ws-a").map { it.id })
            assertEquals(listOf("b"), store.sessionsForWorkspace("/ws-b").map { it.id })
        }
    }

    // ---- XP ledger ---------------------------------------------------------

    @Test
    fun xpAwardsMatchTheSharedCurve() {
        SharedDb.inMemory().use { db ->
            val xp = DbXpLedger(db, sessionId = "s1")
            val now = System.currentTimeMillis()
            xp.awardTurn("first turn", now)
            xp.awardTurn("second turn", now + 1)
            val s = xp.summary(now + 2)
            // Two turns, no streak bonus within the same day's first-award rule.
            assertEquals(10L, s.totalXp)
            assertEquals(0, s.level) // 10 XP is below the first 100-XP level threshold
            assertEquals(100L, s.nextLevelXp)
            assertEquals(2, s.recent.count { it.kind == "TURN_COMPLETED" })
            assertTrue(s.recent.none { it.kind == "DAILY_STREAK_BONUS" })
        }
    }

    @Test
    fun goalAwardPaysStreakBonusOnTheFirstAwardOfASecondDay() {
        SharedDb.inMemory().use { db ->
            val xp = DbXpLedger(db)
            val now = System.currentTimeMillis()
            val yesterday = now - XpDay.MILLIS_PER_DAY
            xp.awardTurn("day 1", yesterday)
            xp.awardGoal("ship it", now)
            val s = xp.summary(now)
            // 5 (turn) + 50 (goal) + 20 (2-day streak bonus, 10/day capped 50)
            assertEquals(75L, s.totalXp)
            assertEquals(1, s.recent.count { it.kind == "DAILY_STREAK_BONUS" })
            assertEquals(2, s.streakDays)
        }
    }

    @Test
    fun legacyJsonLedgerImportsOnce() {
        SharedDb.inMemory().use { db ->
            val home = createTempDirectory(prefix = "xencode-legacy")
            try {
                val f = java.io.File(home.toFile(), "pc-xp-ledger.json")
                f.writeText(
                    """[{"kind":"TURN_COMPLETED","amount":5,"label":"old turn","at":1600000000000},""" +
                        """{"kind":"GOAL_COMPLETED","amount":50,"label":"old goal","at":1600000000001}]"""
                )
                val xp = DbXpLedger(db)
                assertEquals(2, xp.importLegacyJson(f))
                assertEquals(55L, xp.summary().totalXp)
                // Second import is a no-op (table no longer empty).
                assertEquals(0, xp.importLegacyJson(f))
                assertEquals(55L, xp.summary().totalXp)
                // Missing file: no-op.
                assertEquals(0, xp.importLegacyJson(java.io.File(home.toFile(), "nope.json")))
            } finally {
                home.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun xpRowsUseTheAndroidEventVocabulary() {
        SharedDb.inMemory().use { db ->
            val xp = DbXpLedger(db, sessionId = "s9")
            xp.awardTurn("hello", 1600000000000)
            val raw = db.withConnection { c ->
                c.createStatement().executeQuery(
                    "SELECT sessionId, kind, amount, label, createdAt FROM xp_events"
                ).use { rs ->
                    buildList {
                        while (rs.next()) add(
                            Triple(rs.getString("kind"), rs.getLong("amount"), rs.getString("sessionId"))
                        )
                    }
                }
            }
            assertEquals(listOf(Triple("TURN_COMPLETED", 5L, "s9")), raw)
        }
    }

    @Test
    fun persistenceSurvivesReopen() {
        val home = createTempDirectory(prefix = "xencode-reopen")
        try {
            val path = home.resolve("db").toString()
            val now = System.currentTimeMillis()
            SharedDb.open(path).use { db ->
                val store = SharedChatStore(db)
                store.upsertSession(SharedChatStore.SessionRow("keep", "kept", now, now, "/ws"))
                store.appendMessage(SharedChatStore.MessageRow("m1", "keep", "USER", "hi", now))
                DbXpLedger(db).awardTurn("turn", now)
            }
            SharedDb.open(path).use { db ->
                assertEquals("kept", SharedChatStore(db).sessionRow("keep")?.title)
                assertEquals(1, SharedChatStore(db).messageCount("keep"))
                assertEquals(5L, DbXpLedger(db).summary().totalXp)
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}

/** MILLIS_PER_DAY re-exported for the tests without opening XpMath. */
private object XpDay {
    const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
}
