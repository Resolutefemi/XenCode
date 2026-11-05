package com.xencode.core.db

import java.sql.ResultSet

/**
 * Chat-side store over [SharedDb]: the PC writer for the shared
 * `chat_sessions` / `agent_messages` / `llm_call_records` tables — the same
 * tables the Android app fills through Room. Rows are schema-compatible with
 * `ChatSessionEntity` / `AgentMessageEntity` / `LlmCallRecordEntity`
 * (defaults mirrored from those entities), so a sync layer can copy rows in
 * either direction without translation.
 *
 * Message roles use the app's `MessageRole` values: USER, ASSISTANT, TOOL.
 */
class SharedChatStore(private val db: SharedDb) {

    // ---- Row models (defaults mirror the Room entities) ------------------

    data class SessionRow(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val workspacePath: String = "",
        val mode: String = "BUILD",
        val reasoningEffort: String = "MEDIUM",
        val providerId: String? = null,
        val model: String? = null,
        val totalInputTokens: Int = 0,
        val totalOutputTokens: Int = 0,
        val lastInputTokens: Int = 0,
        val isPinned: Boolean = false,
        val parentId: String? = null,
        val subagentType: String? = null
    )

    data class MessageRow(
        val id: String,
        val sessionId: String,
        val role: String,
        val content: String,
        val timestamp: Long,
        val toolCallsJson: String? = null,
        val toolCallId: String? = null,
        val toolName: String? = null,
        val toolArgs: String? = null,
        val isError: Boolean = false,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0
    )

    data class LlmCallRow(
        val sessionId: String? = null,
        val providerId: String? = null,
        val model: String? = null,
        val kind: String = "chat",
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val ttfbMillis: Int? = null,
        val durationMillis: Int? = null,
        val status: String = "success",
        val errorMessage: String? = null,
        val stopReason: String? = null,
        val createdAt: Long
    )

    // ---- Sessions ---------------------------------------------------------

    /** Inserts or updates a session row; returns true when the row was written. */
    fun upsertSession(s: SessionRow): Boolean = db.withConnection { c ->
        c.prepareStatement(UPSERT_SESSION).bindAndExec(
            s.id, s.title, s.createdAt, s.updatedAt, s.workspacePath, s.mode, s.reasoningEffort,
            s.providerId, s.model, s.totalInputTokens, s.totalOutputTokens, s.lastInputTokens, s.isPinned
        )
    }

    /** Adds [inputDelta]/[outputDelta] tokens to a session's running totals. */
    fun addSessionTokens(id: String, inputDelta: Int, outputDelta: Int, lastInput: Int? = null): Boolean =
        db.withConnection { c ->
            c.prepareStatement(ADD_TOKENS).bindAndExec(
                inputDelta, outputDelta, lastInput ?: inputDelta, id
            )
        }

    fun sessionRow(id: String): SessionRow? = db.withConnection { c ->
        c.prepareStatement("SELECT * FROM `chat_sessions` WHERE `id` = ?").apply { setString(1, id) }
            .executeQuery().use { rs -> if (rs.next()) rs.toSessionRow() else null }
    }

    /** Sessions for one workspace, newest activity first. */
    fun sessionsForWorkspace(workspacePath: String, limit: Int = 50): List<SessionRow> = db.withConnection { c ->
        c.prepareStatement("SELECT * FROM `chat_sessions` WHERE `workspacePath` = ? ORDER BY `updatedAt` DESC LIMIT ?")
            .apply { setString(1, workspacePath); setInt(2, limit) }
            .executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toSessionRow()) } }
    }

    // ---- Messages ----------------------------------------------------------

    /** Appends one message row (id is generated: `m<base36 time><counter>`). */
    fun appendMessage(m: MessageRow): Boolean = db.withConnection { c ->
        c.prepareStatement(INSERT_MESSAGE).bindAndExec(
            m.id, m.sessionId, m.role, m.content, m.timestamp, m.toolCallsJson, m.toolCallId,
            m.toolName, m.toolArgs, m.isError, m.inputTokens, m.outputTokens
        )
    }

    fun newMessageId(now: Long = System.currentTimeMillis()): String =
        "m" + java.lang.Long.toString(now, 36) + java.lang.Long.toString(
            MSG_ID_COUNTER.incrementAndGet(), 36
        )

    fun messagesFor(sessionId: String): List<MessageRow> = db.withConnection { c ->
        c.prepareStatement("SELECT * FROM `agent_messages` WHERE `sessionId` = ? ORDER BY `timestamp` ASC, `id` ASC")
            .apply { setString(1, sessionId) }
            .executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toMessageRow()) } }
    }

    fun messageCount(sessionId: String): Int = db.withConnection { c ->
        c.prepareStatement("SELECT COUNT(*) FROM `agent_messages` WHERE `sessionId` = ?")
            .apply { setString(1, sessionId) }
            .executeQuery().use { rs -> rs.next(); rs.intOr(1) }
    }

    // ---- LLM call records ----------------------------------------------------

    /** Inserts one llm_call_records row; returns the generated id (or -1 on failure). */
    fun insertLlmCall(r: LlmCallRow): Long = db.withConnection { c ->
        c.prepareStatement(INSERT_LLM_CALL, java.sql.Statement.RETURN_GENERATED_KEYS).bindAndExec(
            r.sessionId, r.providerId, r.model, r.kind, r.inputTokens, r.outputTokens,
            r.ttfbMillis, r.durationMillis, r.status, r.errorMessage, r.stopReason, r.createdAt
        )
        c.createStatement().executeQuery("SELECT last_insert_rowid()").use { rs ->
            if (rs.next()) rs.getLong(1) else -1L
        }
    }

    data class LlmTotals(val calls: Int, val inputTokens: Long, val outputTokens: Long, val errors: Int)

    /** Lifetime token totals across all front ends (the `/costs` data source). */
    fun llmTotals(): LlmTotals = db.withConnection { c ->
        c.createStatement().executeQuery(
            "SELECT COUNT(*), COALESCE(SUM(inputTokens),0), COALESCE(SUM(outputTokens),0), " +
                "COALESCE(SUM(CASE WHEN status='error' THEN 1 ELSE 0 END),0) FROM `llm_call_records`"
        ).use { rs ->
            rs.next()
            LlmTotals(rs.intOr(1), rs.getLong(2), rs.getLong(3), rs.intOr(4))
        }
    }

    // ---- Row mapping ---------------------------------------------------------

    private fun ResultSet.toSessionRow() = SessionRow(
        id = getString("id"),
        title = getString("title"),
        createdAt = getLong("createdAt"),
        updatedAt = getLong("updatedAt"),
        workspacePath = getString("workspacePath") ?: "",
        mode = getString("mode") ?: "BUILD",
        reasoningEffort = getString("reasoningEffort") ?: "MEDIUM",
        providerId = strOr("providerId"),
        model = strOr("model"),
        totalInputTokens = intOr("totalInputTokens"),
        totalOutputTokens = intOr("totalOutputTokens"),
        lastInputTokens = intOr("lastInputTokens"),
        isPinned = intOr("isPinned") != 0,
        parentId = strOr("parentId"),
        subagentType = strOr("subagentType")
    )

    private fun ResultSet.toMessageRow() = MessageRow(
        id = getString("id"),
        sessionId = getString("sessionId"),
        role = getString("role"),
        content = getString("content") ?: "",
        timestamp = getLong("timestamp"),
        toolCallsJson = strOr("toolCallsJson"),
        toolCallId = strOr("toolCallId"),
        toolName = strOr("toolName"),
        toolArgs = strOr("toolArgs"),
        isError = intOr("isError") != 0,
        inputTokens = intOr("inputTokens"),
        outputTokens = intOr("outputTokens")
    )

    private companion object {
        val MSG_ID_COUNTER = java.util.concurrent.atomic.AtomicLong(0)

        // ON CONFLICT preserves token totals accumulated via addSessionTokens().
        private val UPSERT_SESSION =
            "INSERT INTO `chat_sessions` (`id`,`title`,`createdAt`,`updatedAt`,`workspacePath`,`mode`," +
                "`modeBeforePlan`,`reasoningEffort`,`providerId`,`model`,`totalInputTokens`," +
                "`totalOutputTokens`,`lastInputTokens`,`isPinned`) VALUES (?,?,?,?,?,?,NULL,?,?,?,?,?,?,?) " +
                "ON CONFLICT(`id`) DO UPDATE SET `title`=excluded.`title`, `updatedAt`=excluded.`updatedAt`, " +
                "`workspacePath`=excluded.`workspacePath`, `providerId`=excluded.`providerId`, " +
                "`model`=excluded.`model`"
        private val ADD_TOKENS =
            "UPDATE `chat_sessions` SET `totalInputTokens` = `totalInputTokens` + ?, " +
                "`totalOutputTokens` = `totalOutputTokens` + ?, `lastInputTokens` = ? WHERE `id` = ?"
        private val INSERT_MESSAGE =
            "INSERT INTO `agent_messages` (`id`,`sessionId`,`role`,`content`,`timestamp`,`toolCallsJson`," +
                "`toolCallId`,`toolName`,`toolArgs`,`isError`,`inputTokens`,`outputTokens`,`isCompacted`," +
                "`isContextSummary`,`isCompactionMarker`,`cachedInputTokens`) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,0,0,0)"
        private val INSERT_LLM_CALL =
            "INSERT INTO `llm_call_records` (`sessionId`,`providerId`,`model`,`kind`,`inputTokens`," +
                "`outputTokens`,`ttfbMillis`,`durationMillis`,`status`,`errorMessage`,`stopReason`,`createdAt`," +
                "`cachedInputTokens`,`cacheCreationTokens`,`retryCount`) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,0,0)"
    }
}
