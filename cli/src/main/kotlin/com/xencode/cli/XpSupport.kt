package com.xencode.cli

import com.xencode.core.telemetry.XpLedger

/**
 * Pure rendering for `xencode xp`. Mirrors the Android `/xp` table (same
 * columns, same kind labels) so both clients read identically for the same
 * ledger state.
 */

/** Human label for a ledger event kind; unknown kinds fall back to the raw name. */
fun kindLabel(kind: String): String = when (kind.uppercase()) {
    "TURN_COMPLETED" -> "Turn completed"
    "GOAL_COMPLETED" -> "Goal completed"
    "DAILY_STREAK_BONUS" -> "Daily streak bonus"
    "MANUAL" -> "Manual adjustment"
    else -> kind
}

/** Full `/xp`-style report: header line, item table, recent events. */
fun renderXpSummary(s: XpLedger.Summary): String = buildString {
    appendLine("**XP** — Level ${s.level} · ${s.totalXp} XP total · ${s.streakDays}d streak")
    appendLine()
    appendLine("| Item | Value |")
    appendLine("|---|---|")
    appendLine("| Level | ${s.level} (next at ${s.nextLevelXp} XP) |")
    appendLine("| Total XP | ${s.totalXp} |")
    appendLine("| Earned today | +${s.todayXp} |")
    appendLine("| Streak | ${s.streakDays} day(s) |")
    if (s.recent.isNotEmpty()) {
        appendLine()
        appendLine("Recent events:")
        for (e in s.recent) {
            appendLine("- ${kindLabel(e.kind)} +${e.amount} — ${e.label}")
        }
    }
}.trimEnd()
