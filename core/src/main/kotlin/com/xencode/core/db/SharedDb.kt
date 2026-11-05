package com.xencode.core.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * The shared XenCode database — the PC-side opening of the same SQLite schema
 * the Android app persists through Room (`AgentDatabase`, DATABASE_NAME
 * `aicode_agent_db`). The Kotlin app's database contract is the product-wide
 * contract: the desktop app and the CLI write their chat sessions, messages,
 * XP events and LLM call records into tables with the exact columns, indices
 * and affinities exported in `app/schemas/.../58.json` (plus the 59
 * `xp_events` migration), so rows are copy-compatible with the app's store and
 * a sync layer can move them 1:1.
 *
 * On the PC the file lives at `~/.xencode/aicode_agent_db` (one database for
 * every workspace and front end — the `chat_sessions.workspacePath` column
 * keeps workspaces apart, mirroring the app's global database).
 *
 * Single connection + one coarse lock: desktop/CLI writes are short and
 * infrequent, and sqlite-jdbc connections are not safe for concurrent
 * statements. WAL is enabled so a reader (e.g. a future sync agent) never
 * blocks a writer.
 */
class SharedDb private constructor(val file: File?, private val url: String) : AutoCloseable {

    private val conn: Connection = DriverManager.getConnection(url).apply {
        autoCommit = true
    }
    private val lock = Any()

    /** Runs [block] with the shared connection under the instance lock. */
    fun <T> withConnection(block: (Connection) -> T): T = synchronized(lock) { block(conn) }

    /** Idempotent schema creation, byte-compatible with the exported Room schema. */
    fun ensureSchema() {
        synchronized(lock) {
            for (ddl in SCHEMA_DDL) {
                conn.createStatement().use { it.execute(ddl) }
            }
        }
    }

    override fun close() {
        synchronized(lock) { runCatching { conn.close() } }
    }

    companion object {
        /** Same name as the Android Room database (AgentDatabase.DATABASE_NAME). */
        const val DATABASE_NAME = "aicode_agent_db"

        fun xencodeHome(): File =
            File((System.getProperty("user.home") ?: ".").trimEnd('/').ifEmpty { "." }, ".xencode")

        fun defaultPath(): String = File(xencodeHome(), DATABASE_NAME).path

        /** Opens (and creates) the shared database at [path]; `:memory:` is allowed for tests. */
        fun open(path: String): SharedDb {
            if (path != IN_MEMORY) File(path).parentFile?.mkdirs()
            val db = SharedDb(
                file = if (path == IN_MEMORY) null else File(path),
                url = "jdbc:sqlite:$path"
            )
            db.withConnection { c ->
                c.createStatement().use { st ->
                    st.execute("PRAGMA journal_mode=WAL")
                    st.execute("PRAGMA busy_timeout=5000")
                }
            }
            db.ensureSchema()
            return db
        }

        /** The production database for this machine: `~/.xencode/aicode_agent_db`. */
        fun openDefault(): SharedDb = open(defaultPath())

        /** In-memory database for tests. */
        fun inMemory(): SharedDb = open(IN_MEMORY)

        private const val IN_MEMORY = ":memory:"

        // ------------------------------------------------------------------
        // DDL: generated from the exported Room schema (58.json) and the
        // 59_add_xp_events.sql migration. Keep in sync with those files.
        // ------------------------------------------------------------------
        internal val SCHEMA_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `chat_sessions` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `workspacePath` TEXT NOT NULL, " +
                "`mode` TEXT NOT NULL, `modeBeforePlan` TEXT, `reasoningEffort` TEXT NOT NULL, " +
                "`providerId` TEXT, `model` TEXT, `totalInputTokens` INTEGER NOT NULL, " +
                "`totalOutputTokens` INTEGER NOT NULL, `lastInputTokens` INTEGER NOT NULL, " +
                "`isPinned` INTEGER NOT NULL, `parentId` TEXT, `subagentType` TEXT, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_chat_sessions_workspacePath` ON `chat_sessions` (`workspacePath`)",
            "CREATE TABLE IF NOT EXISTS `agent_messages` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
                "`role` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                "`toolCallsJson` TEXT, `toolCallId` TEXT, `toolName` TEXT, `toolArgs` TEXT, " +
                "`isError` INTEGER NOT NULL, `reasoning` TEXT, `signature` TEXT, `attachmentsJson` TEXT, " +
                "`isCompacted` INTEGER NOT NULL, `isContextSummary` INTEGER NOT NULL, " +
                "`isCompactionMarker` INTEGER NOT NULL, `inputTokens` INTEGER NOT NULL, " +
                "`outputTokens` INTEGER NOT NULL, `thinkingBlocksJson` TEXT, `cachedInputTokens` INTEGER NOT NULL, " +
                "`isContextExcluded` INTEGER NOT NULL DEFAULT 0, `compactedBySummaryId` TEXT, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_agent_messages_sessionId` ON `agent_messages` (`sessionId`)",
            "CREATE TABLE IF NOT EXISTS `xp_events` (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "sessionId TEXT NOT NULL, " +
                "kind TEXT NOT NULL, " +
                "amount INTEGER NOT NULL, " +
                "label TEXT NOT NULL, " +
                "createdAt INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS index_xp_events_createdAt ON xp_events(createdAt)",
            "CREATE INDEX IF NOT EXISTS index_xp_events_sessionId ON xp_events(sessionId)",
            "CREATE TABLE IF NOT EXISTS `llm_call_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` TEXT, `providerId` TEXT, `model` TEXT, `reasoningEffort` TEXT, `kind` TEXT NOT NULL, " +
                "`inputTokens` INTEGER NOT NULL, `outputTokens` INTEGER NOT NULL, `cachedInputTokens` INTEGER NOT NULL, " +
                "`cacheCreationTokens` INTEGER NOT NULL, `ttfbMillis` INTEGER, `durationMillis` INTEGER, " +
                "`status` TEXT NOT NULL, `errorMessage` TEXT, `stopReason` TEXT, `retryCount` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_llm_call_records_createdAt` ON `llm_call_records` (`createdAt`)",
            "CREATE INDEX IF NOT EXISTS `index_llm_call_records_provider_model` ON `llm_call_records` (`providerId`, `model`)"
        )
    }
}

/** Shared JDBC helpers for the store classes. */
internal fun PreparedStatement.bindAndExec(vararg args: Any?): Boolean {
    args.forEachIndexed { i, v ->
        when (v) {
            null -> setNull(i + 1, java.sql.Types.NULL)
            is String -> setString(i + 1, v)
            is Long -> setLong(i + 1, v)
            is Int -> setInt(i + 1, v)
            is Boolean -> setInt(i + 1, if (v) 1 else 0)
            is Double -> setDouble(i + 1, v)
            else -> setString(i + 1, v.toString())
        }
    }
    return executeUpdate() > 0
}

/** Reads an Int column that may be NULL (0 for null). */
internal fun ResultSet.intOr(columnLabel: String, default: Int = 0): Int =
    getInt(columnLabel).let { if (wasNull()) default else it }

/** Index variant for aggregate selects. */
internal fun ResultSet.intOr(columnIndex: Int): Int =
    getInt(columnIndex).let { if (wasNull()) 0 else it }

/** Reads a String column that may be NULL. */
internal fun ResultSet.strOr(columnLabel: String): String? = getString(columnLabel)
