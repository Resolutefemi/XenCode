package com.xencode.core.telemetry

/**
 * The write/read surface both XP ledgers implement: the file-backed
 * [XpLedger] (`~/.xencode/pc-xp-ledger.json`, the CLI default) and the
 * shared-database ledger (`DbXpLedger`, `xp_events` in `aicode_agent_db`,
 * the desktop default). Award rules live in [XpMath]; implementations only
 * persist rows and derive summaries.
 */
interface XpStore {
    /** All ledger events, oldest first. */
    fun load(): List<XpLedger.Event>

    /** +5 per successfully completed chat turn. */
    fun awardTurn(label: String, now: Long = System.currentTimeMillis())

    /** +50 per completed goal. */
    fun awardGoal(goalTitle: String, now: Long = System.currentTimeMillis())

    /** Derived view: totals, level, streak and the most recent rows. */
    fun summary(now: Long = System.currentTimeMillis()): XpLedger.Summary
}
