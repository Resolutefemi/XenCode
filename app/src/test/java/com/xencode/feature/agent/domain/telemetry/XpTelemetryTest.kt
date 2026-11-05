package com.xencode.feature.agent.domain.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the XP curve, award amounts, and streak math. These numbers are a product
 * contract (CLI / desktop render the same ledger), so every change is deliberate.
 */
class XpTelemetryTest {

    // ---- Curve ----

    @Test
    fun levelCurve_quadratic() {
        assertEquals(100L, XpTelemetry.xpForLevel(1))
        assertEquals(400L, XpTelemetry.xpForLevel(2))
        assertEquals(900L, XpTelemetry.xpForLevel(3))
    }

    @Test
    fun levelFor_boundaries() {
        assertEquals(0, XpTelemetry.levelFor(0))
        assertEquals(0, XpTelemetry.levelFor(99))
        assertEquals(1, XpTelemetry.levelFor(100))
        assertEquals(1, XpTelemetry.levelFor(399))
        assertEquals(2, XpTelemetry.levelFor(400))
        assertEquals(10, XpTelemetry.levelFor(10_000))
    }

    @Test
    fun nextLevelXp_followsCurve() {
        assertEquals(100L, XpTelemetry.nextLevelXp(0))
        assertEquals(400L, XpTelemetry.nextLevelXp(1))
    }

    // ---- Awards ----

    @Test
    fun awards_fixedAmounts() {
        assertEquals(5L, XpTelemetry.turnAward())
        assertEquals(50L, XpTelemetry.goalAward("anything"))
    }

    @Test
    fun dailyStreakBonus_skipsDayOne_andCapsAt50() {
        assertEquals(0L, XpTelemetry.dailyStreakBonus(0))
        assertEquals(0L, XpTelemetry.dailyStreakBonus(1))
        assertEquals(20L, XpTelemetry.dailyStreakBonus(2))
        assertEquals(50L, XpTelemetry.dailyStreakBonus(5))
        assertEquals(50L, XpTelemetry.dailyStreakBonus(30)) // 10 * 30 would be 300; capped
    }

    // ---- Streaks ----

    @Test
    fun currentStreak_emptyIsZero() {
        assertEquals(0, XpTelemetry.currentStreak(emptySet(), todayEpochDay = 100))
    }

    @Test
    fun currentStreak_countsBackFromToday() {
        val days = setOf(98L, 99L, 100L)
        assertEquals(3, XpTelemetry.currentStreak(days, todayEpochDay = 100))
    }

    @Test
    fun currentStreak_gapBreaksIt() {
        val days = setOf(96L, 97L, 100L) // gap at 98-99
        assertEquals(1, XpTelemetry.currentStreak(days, todayEpochDay = 100))
    }

    @Test
    fun currentStreak_aliveThroughYesterday_whenTodayEmpty() {
        val days = setOf(98L, 99L) // nothing today yet
        assertEquals(2, XpTelemetry.currentStreak(days, todayEpochDay = 100))
    }

    @Test
    fun currentStreak_deadWhenYesterdayAlsoEmpty() {
        val days = setOf(95L) // two-day gap
        assertEquals(0, XpTelemetry.currentStreak(days, todayEpochDay = 100))
    }

    @Test
    fun epochDay_matchesUtcDays() {
        assertEquals(0L, XpTelemetry.epochDay(86_399_999L))
        assertEquals(1L, XpTelemetry.epochDay(86_400_000L))
    }

    // ---- Rendering ----

    @Test
    fun summaryLine_formatsLevelXpStreak() {
        val line = XpTelemetry.summaryLine(totalXp = 420, streakDays = 3)
        assertEquals("Level 2 · 420 XP (next level at 900) · 3d streak", line)
    }

    @Test
    fun kindFromName_isCaseInsensitive_andNullForUnknown() {
        assertEquals(XpTelemetry.Kind.GOAL_COMPLETED, XpTelemetry.Kind.fromName("goal_completed"))
        assertEquals(XpTelemetry.Kind.TURN_COMPLETED, XpTelemetry.Kind.fromName("Turn_Completed"))
        assertEquals(null, XpTelemetry.Kind.fromName("nope"))
    }
}
