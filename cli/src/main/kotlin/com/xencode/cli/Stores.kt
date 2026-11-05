package com.xencode.cli

import com.xencode.core.db.DbXpLedger
import com.xencode.core.db.SharedChatStore
import com.xencode.core.db.SharedDb
import com.xencode.core.telemetry.XpLedger
import com.xencode.core.telemetry.XpStore
import java.io.File

/**
 * Machine-wide (per-user) storage locations for the PC line.
 *
 * Two stores:
 * - the shared database `~/.xencode/aicode_agent_db` (the Kotlin app's
 *   Room schema; the desktop app and the CLI both write here — chat sessions,
 *   messages, llm_call_records and xp_events),
 * - the legacy per-machine JSON ledger `~/.xencode/pc-xp-ledger.json`,
 *   imported into the database on first use and still written by nothing
 *   (kept for the one-time migration path).
 *
 * The config store lives at `~/.xencode/pc-config.json` and is managed by
 * core's PcConfigStore.
 */
object MachineStores {

    /** `~/.xencode`, the shared home for PC-line state. */
    fun xencodeHome(): File = File(homeDir(), ".xencode")

    /** Per-machine XP ledger at `~/.xencode/pc-xp-ledger.json` (legacy import source). */
    fun ledger(): XpLedger = XpLedger(root = homeDir() + "/.xencode")

    fun ledgerFile(): File = File(xencodeHome(), "pc-xp-ledger.json")

    /** `~/.xencode/aicode_agent_db` — the shared database path on this machine. */
    fun sharedDbPath(): String = SharedDb.defaultPath()

    @Volatile
    private var sharedDb: SharedDb? = null

    /** Process-wide shared database connection (CLI process lifetime). */
    fun sharedDb(): SharedDb {
        sharedDb?.let { return it }
        synchronized(this) {
            sharedDb?.let { return it }
            return SharedDb.open(sharedDbPath()).also { sharedDb = it }
        }
    }

    fun sharedChatStore(): SharedChatStore = SharedChatStore(sharedDb())

    /** XP ledger backed by the shared database; imports the legacy JSON once. */
    fun sharedXpStore(): XpStore {
        val ledger = DbXpLedger(sharedDb())
        runCatching { ledger.importLegacyJson(ledgerFile()) }
        return ledger
    }

    private fun homeDir(): String =
        (System.getProperty("user.home") ?: ".").trimEnd('/').ifEmpty { "." }
}
