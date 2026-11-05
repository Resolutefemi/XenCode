package com.xencode.cli

import com.xencode.core.telemetry.XpLedger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * XP rendering: kind labels and the /xp-shaped table (same columns as Android).
 */
class XpRenderTest {

    @Test
    fun kindLabelsMatchAndroid() {
        assertEquals("Turn completed", kindLabel("TURN_COMPLETED"))
        assertEquals("Goal completed", kindLabel("goal_completed")) // case-insensitive like Android
        assertEquals("Daily streak bonus", kindLabel("DAILY_STREAK_BONUS"))
        assertEquals("Manual adjustment", kindLabel("MANUAL"))
        assertEquals("SOMETHING_ELSE", kindLabel("SOMETHING_ELSE")) // fallback keeps the raw name
    }

    @Test
    fun summaryTableHasAndroidColumns() {
        val s = XpLedger.Summary(
            totalXp = 55L,
            level = 1,
            nextLevelXp = 400L,
            streakDays = 2,
            todayXp = 55L,
            recent = listOf(
                XpLedger.Event("TURN_COMPLETED", 5L, "turn 1", 1L),
                XpLedger.Event("GOAL_COMPLETED", 50L, "ship it", 2L)
            )
        )
        val out = renderXpSummary(s)
        assertEquals(
            "**XP** — Level 1 · 55 XP total · 2d streak",
            out.lineSequence().first()
        )
        assertTrue(out.contains("| Level | 1 (next at 400 XP) |"))
        assertTrue(out.contains("| Total XP | 55 |"))
        assertTrue(out.contains("| Earned today | +55 |"))
        assertTrue(out.contains("| Streak | 2 day(s) |"))
        assertTrue(out.contains("Recent events:"))
        assertTrue(out.contains("- Turn completed +5 — turn 1"))
        assertTrue(out.contains("- Goal completed +50 — ship it"))
    }

    @Test
    fun summaryWithoutEventsOmitsRecentBlock() {
        val s = XpLedger.Summary(0L, 0, 100L, 0, 0L, emptyList())
        val out = renderXpSummary(s)
        assertFalseContains(out, "Recent events:")
    }

    private fun assertFalseContains(haystack: String, needle: String) {
        assertEquals(false, haystack.contains(needle), "expected `$needle` to be absent")
    }
}
