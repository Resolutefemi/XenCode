package com.xencode.core.db

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xencode.core.telemetry.XpLedger
import com.xencode.core.telemetry.XpMath
import com.xencode.core.telemetry.XpStore
import java.io.File

/**
 * The desktop/CLI XP ledger backed by the shared database: rows land in
 * `xp_events` (`aicode_agent_db`) — the same table the Android app writes
 * through Room's `XpEventDao`, with identical kind/amount/label semantics.
 * Summaries are derived with [XpMath], so a level means the same thing on
 * every front end.
 *
 * [sessionId] tags rows for per-session analytics; the Android entity reserves
 * empty strings for global events, which is what the PC line writes.
 *
 * [importLegacyJson] migrates the old per-machine file ledger
 * (`~/.xencode/pc-xp-ledger.json`) once, so switching to the DB never loses a
 * level or a streak.
 */
class DbXpLedger(
    private val db: SharedDb,
    private val sessionId: String = ""
) : XpStore {

    private val gson = Gson()

    override fun load(): List<XpLedger.Event> = db.withConnection { c ->
        c.createStatement().executeQuery(
            "SELECT `kind`, `amount`, `label`, `createdAt` FROM `xp_events` ORDER BY `id` ASC"
        ).use { rs ->
            buildList { while (rs.next()) add(rs.toEvent()) }
        }
    }

    override fun awardTurn(label: String, now: Long) {
        insert("TURN_COMPLETED", XpMath.turnAward(), label, now)
        maybeStreakBonus(now)
    }

    override fun awardGoal(goalTitle: String, now: Long) {
        insert("GOAL_COMPLETED", XpMath.goalAward(), goalTitle, now)
        maybeStreakBonus(now)
    }

    override fun summary(now: Long): XpLedger.Summary {
        val all = load()
        val total = all.sumOf { it.amount }
        val dayStart = now - (now % XpMath.MILLIS_PER_DAY)
        val days = all.mapTo(mutableSetOf()) { XpMath.epochDay(it.at) }
        return XpLedger.Summary(
            totalXp = total,
            level = XpMath.levelFor(total),
            nextLevelXp = XpMath.nextLevelXp(XpMath.levelFor(total)),
            streakDays = XpMath.currentStreak(days, XpMath.epochDay(now)),
            todayXp = all.filter { it.at >= dayStart }.sumOf { it.amount },
            recent = all.takeLast(8)
        )
    }

    /**
     * Imports the legacy JSON ledger when the table is still empty.
     * @return the number of imported rows (0 when nothing was imported).
     */
    fun importLegacyJson(file: File): Int {
        if (!file.isFile) return 0
        if (countAll() > 0) return 0
        val rows = runCatching {
            val type = object : TypeToken<List<XpLedger.Event>>() {}.type
            gson.fromJson<List<XpLedger.Event>>(file.readText(Charsets.UTF_8), type).orEmpty()
        }.getOrElse { return 0 }
        rows.forEach { insert(it.kind, it.amount, it.label, it.at) }
        return rows.size
    }

    fun countAll(): Int = db.withConnection { c ->
        c.createStatement().executeQuery("SELECT COUNT(*) FROM `xp_events`").use { rs ->
            rs.next(); rs.intOr(1)
        }
    }

    private fun insert(kind: String, amount: Long, label: String, at: Long): Boolean = db.withConnection { c ->
        c.prepareStatement(INSERT).bindAndExec(sessionId, kind, amount, label, at)
    }

    /**
     * First award of the day pays the streak bonus — the same rule as
     * [XpLedger] (bonus counted only when this insert was the day's first).
     */
    private fun maybeStreakBonus(now: Long) {
        val dayStart = now - (now % XpMath.MILLIS_PER_DAY)
        if (countSince(dayStart) > 1) return
        val days = load().mapTo(mutableSetOf()) { XpMath.epochDay(it.at) }
        val streak = XpMath.currentStreak(days, XpMath.epochDay(now))
        val bonus = XpMath.dailyStreakBonus(streak)
        if (bonus > 0) insert("DAILY_STREAK_BONUS", bonus, "${streak}d streak kept alive", now)
    }

    private fun countSince(dayStart: Long): Int = db.withConnection { c ->
        c.prepareStatement("SELECT COUNT(*) FROM `xp_events` WHERE `createdAt` >= ?")
            .apply { setLong(1, dayStart) }
            .executeQuery().use { rs -> rs.next(); rs.intOr(1) }
    }

    private fun java.sql.ResultSet.toEvent() = XpLedger.Event(
        kind = getString("kind"),
        amount = getLong("amount"),
        label = getString("label") ?: "",
        at = getLong("createdAt")
    )

    private companion object {
        private val INSERT =
            "INSERT INTO `xp_events` (`sessionId`,`kind`,`amount`,`label`,`createdAt`) VALUES (?,?,?,?,?)"
    }
}
