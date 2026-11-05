package com.xencode.core.telemetry

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * XP curve + streak math — the numeric contract shared with the Android app
 * (app/.../domain/telemetry/XpTelemetry.kt). The Android side stores events in
 * Room; the PC line persists them in a local JSON ledger per machine
 * (`~/.xencode/pc-xp-ledger.json`). The curves are identical so levels are
 * comparable across devices.
 */
object XpMath {

    const val LEVEL_CURVE_FACTOR = 100

    fun xpForLevel(level: Int): Long = LEVEL_CURVE_FACTOR.toLong() * level * level

    fun levelFor(totalXp: Long): Int {
        var level = 0
        while (xpForLevel(level + 1) <= totalXp) level++
        return level
    }

    fun nextLevelXp(level: Int): Long = xpForLevel(level + 1)

    const val TURN_AWARD = 5L
    const val GOAL_AWARD = 50L
    const val STREAK_BONUS_PER_DAY = 10L
    const val STREAK_BONUS_DAILY_CAP = 50L

    fun turnAward(): Long = TURN_AWARD

    fun goalAward(): Long = GOAL_AWARD

    fun dailyStreakBonus(streakDays: Int): Long =
        if (streakDays <= 1) 0L else minOf(STREAK_BONUS_PER_DAY * streakDays, STREAK_BONUS_DAILY_CAP)

    const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    fun epochDay(ts: Long): Long = ts / MILLIS_PER_DAY

    fun currentStreak(dayEpochs: Set<Long>, todayEpochDay: Long): Int {
        if (dayEpochs.isEmpty()) return 0
        if (todayEpochDay !in dayEpochs && (todayEpochDay - 1) !in dayEpochs) return 0
        var day = todayEpochDay
        if (day !in dayEpochs) day--
        var streak = 0
        while (day in dayEpochs) {
            streak++
            day--
        }
        return streak
    }

    fun summaryLine(totalXp: Long, streakDays: Int): String {
        val level = levelFor(totalXp)
        return "Level $level · $totalXp XP (next at ${nextLevelXp(level)}) · ${streakDays}d streak"
    }
}

/**
 * File-backed XP ledger for the PC line. Append-only rows; summaries derived on read.
 * Not synchronized with the Android Room ledger — each machine keeps its own,
 * the curves match so a level means the same thing everywhere.
 */
class XpLedger(private val file: File) {

    constructor(root: String) : this(File(root.trimEnd('/') + "/pc-xp-ledger.json"))

    data class Event(val kind: String, val amount: Long, val label: String, val at: Long)

    private val gson = Gson()

    /** Backing ledger file, for callers that need to share the same store. */
    fun backingFile(): File = file

    fun load(): List<Event> = runCatching {
        val type = object : TypeToken<List<Event>>() {}.type
        gson.fromJson<List<Event>>(file.readText(Charsets.UTF_8), type) ?: emptyList()
    }.getOrElse { emptyList() }

    fun append(event: Event): List<Event> {
        val all = load() + event
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(all), Charsets.UTF_8)
        return all
    }

    fun awardTurn(label: String, now: Long = System.currentTimeMillis()) {
        maybeStreakBonus(append(Event("TURN_COMPLETED", XpMath.turnAward(), label, now)), now)
    }

    fun awardGoal(goalTitle: String, now: Long = System.currentTimeMillis()) {
        maybeStreakBonus(append(Event("GOAL_COMPLETED", XpMath.goalAward(), goalTitle, now)), now)
    }

    private fun maybeStreakBonus(eventsAfter: List<Event>, now: Long) {
        // Bonus applies to the first event of the day only.
        val dayStart = now - (now % XpMath.MILLIS_PER_DAY)
        val today = eventsAfter.count { it.at >= dayStart }
        if (today > 1) return // the award we just appended already counted
        val days = eventsAfter.mapTo(mutableSetOf()) { XpMath.epochDay(it.at) }
        val streak = XpMath.currentStreak(days, XpMath.epochDay(now))
        val bonus = XpMath.dailyStreakBonus(streak)
        if (bonus > 0) append(Event("DAILY_STREAK_BONUS", bonus, "${streak}d streak kept alive", now))
    }

    data class Summary(val totalXp: Long, val level: Int, val nextLevelXp: Long, val streakDays: Int, val todayXp: Long, val recent: List<Event>)

    fun summary(now: Long = System.currentTimeMillis()): Summary {
        val all = load()
        val total = all.sumOf { it.amount }
        val dayStart = now - (now % XpMath.MILLIS_PER_DAY)
        val days = all.mapTo(mutableSetOf()) { XpMath.epochDay(it.at) }
        return Summary(
            totalXp = total,
            level = XpMath.levelFor(total),
            nextLevelXp = XpMath.nextLevelXp(XpMath.levelFor(total)),
            streakDays = XpMath.currentStreak(days, XpMath.epochDay(now)),
            todayXp = all.filter { it.at >= dayStart }.sumOf { it.amount },
            recent = all.takeLast(8)
        )
    }
}
