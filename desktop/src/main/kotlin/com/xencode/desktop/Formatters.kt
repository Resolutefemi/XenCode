package com.xencode.desktop

import com.xencode.core.goal.GoalStatus
import com.xencode.core.telemetry.XpLedger
import com.xencode.core.telemetry.XpMath
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure string/number builders shared by the desktop panes. Deliberately free of
 * Compose imports so every output can be asserted in plain unit tests
 * (see FormattersTest). Strings must stay in English.
 */
object Formatters {

    // ------------------------------------------------------------------
    // Goals
    // ------------------------------------------------------------------

    /** Status glyph shown in front of every goal node (kept in sync with the CLI). */
    fun statusGlyph(status: GoalStatus): String = when (status) {
        GoalStatus.DONE -> "✓"
        GoalStatus.ACTIVE -> "●"
        GoalStatus.READY -> "▸"
        GoalStatus.PENDING -> "·"
        GoalStatus.FAILED -> "✗"
        GoalStatus.CANCELLED -> "⊘"
    }

    /** Same math as GoalDag.progressPercent: an empty DAG counts as 100%. */
    fun percent(done: Int, total: Int): Int = if (total <= 0) 100 else done * 100 / total

    /** Progress header line, e.g. "2/5 done (40%)". */
    fun progressLine(done: Int, total: Int): String = "$done/$total done (${percent(done, total)}%)"

    /** Subline for blocked goals, e.g. "waiting on g1 (Set up repo), g3 (Write tests)". */
    fun waitingOnLine(blockers: List<String>): String? =
        blockers.takeIf { it.isNotEmpty() }?.joinToString(separator = ", ", prefix = "waiting on ")

    /** Parses the "after" field of the add-goal form: "G1 g3, g5" -> [g1, g3, g5]. */
    fun parseIds(raw: String): List<String> =
        raw.split(',', ';', ' ', '\t')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    /** Monospace inline rendering of a tool call, e.g. `[tool] read_file {"path":"README.md"}`. */
    fun toolLine(name: String, argsJson: String): String = "[tool] $name $argsJson"

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    /** Masked API key for display: first 6 characters + "..." (short keys show as-is + "..."). */
    fun maskApiKey(key: String): String = if (key.length <= 6) "$key..." else "${key.take(6)}..."

    // ------------------------------------------------------------------
    // XP / progress
    // ------------------------------------------------------------------

    /** Fraction (0..1) of the way from the current level threshold to the next one. */
    fun levelProgressFraction(totalXp: Long): Float {
        val level = XpMath.levelFor(totalXp)
        val start = XpMath.xpForLevel(level)
        val end = XpMath.xpForLevel(level + 1)
        val span = (end - start).coerceAtLeast(1L)
        return ((totalXp - start).toFloat() / span).coerceIn(0f, 1f)
    }

    /** One-line rendering of a ledger event, e.g. "+50 GOAL_COMPLETED — Ship v1". */
    fun eventLine(event: XpLedger.Event): String = "+${event.amount} ${event.kind} — ${event.label}"

    /** Streak card hint text. */
    fun streakHint(streakDays: Int): String = when {
        streakDays <= 0 -> "Earn XP today to start a streak."
        streakDays == 1 -> "Come back tomorrow to grow your streak (bonus starts on day 2)."
        else -> "Keep it up — the current daily streak bonus is +${XpMath.dailyStreakBonus(streakDays)} XP."
    }

    private val eventTimeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH)

    /** English timestamp for ledger events, independent of the OS locale. */
    fun eventTime(at: Long): String =
        eventTimeFormat.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
}
