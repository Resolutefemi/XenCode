package com.xencode.feature.agent.domain.telemetry

/**
 * Pure XP / streak math. No Android imports — shared logic for the app, and mirrored
 * by the CLI / desktop clients. All curves are deterministic so tests pin them down.
 */
object XpTelemetry {

    /** What happened, stored with each event row. */
    enum class Kind(val label: String) {
        TURN_COMPLETED("Turn completed"),
        GOAL_COMPLETED("Goal completed"),
        DAILY_STREAK_BONUS("Daily streak bonus"),
        MANUAL("Manual adjustment");

        companion object {
            fun fromName(name: String): Kind? =
                entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    }

    // ---- Curve -------------------------------------------------------------
    // Level n is reached at 100 * n^2 total XP: L1 = 100, L2 = 400, L3 = 900 ...
    // Quadratic growth keeps early progress fast and later levels meaningful.

    const val LEVEL_CURVE_FACTOR = 100

    /** Total XP required to reach [level] (level >= 1). */
    fun xpForLevel(level: Int): Long = LEVEL_CURVE_FACTOR.toLong() * level.toLong() * level.toLong()

    /** The level a given [totalXp] has reached (level 0 below 100 XP). */
    fun levelFor(totalXp: Long): Int {
        var level = 0
        while (xpForLevel(level + 1) <= totalXp) level++
        return level
    }

    fun nextLevelXp(level: Int): Long = xpForLevel(level + 1)

    // ---- Awards ------------------------------------------------------------
    // Turn: +5 base. Goal: +50. Streak bonus: +10 * day-count, capped at +50/day.

    const val TURN_AWARD = 5L
    const val GOAL_AWARD = 50L
    const val STREAK_BONUS_PER_DAY = 10L
    const val STREAK_BONUS_DAILY_CAP = 50L

    fun turnAward(): Long = TURN_AWARD

    fun goalAward(goalTitle: String): Long = GOAL_AWARD

    fun dailyStreakBonus(streakDays: Int): Long =
        if (streakDays <= 1) 0L
        else minOf(STREAK_BONUS_PER_DAY * streakDays, STREAK_BONUS_DAILY_CAP)

    // ---- Streak ------------------------------------------------------------
    // A day counts if at least one event exists in it (day = epoch day, UTC).
    // The streak is consecutive days ending today (or yesterday, shown as "keep it going").

    /**
     * @param dayEpochs distinct event days as epoch-day numbers, unsorted.
     * @return the current streak in days: 0 if no events; counts back from [todayEpochDay].
     */
    fun currentStreak(dayEpochs: Set<Long>, todayEpochDay: Long): Int {
        if (dayEpochs.isEmpty()) return 0
        if (todayEpochDay !in dayEpochs) {
            // No event yet today: the streak is "alive" only if yesterday had one; then it
            // still counts (the user has not broken it yet) but shows the same number.
            if (todayEpochDay - 1 !in dayEpochs) return 0
        }
        var day = todayEpochDay
        if (day !in dayEpochs) day-- // anchor to yesterday when today is still empty
        var streak = 0
        while (day in dayEpochs) {
            streak++
            day--
        }
        return streak
    }

    fun epochDay(timestampMillis: Long): Long = timestampMillis / MILLIS_PER_DAY

    const val MILLIS_PER_DAY: Long = 24L * 60 * 60 * 1000

    // ---- Rendering ---------------------------------------------------------

    /** One-line summary for /xp and headers. */
    fun summaryLine(totalXp: Long, streakDays: Int): String {
        val level = levelFor(totalXp)
        val next = nextLevelXp(level)
        return "Level $level · $totalXp XP (next level at $next) · ${streakDays}d streak"
    }
}
