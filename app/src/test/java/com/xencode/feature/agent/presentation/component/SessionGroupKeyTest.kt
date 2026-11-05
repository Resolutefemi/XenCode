package com.xencode.feature.agent.presentation.component

import com.xencode.feature.agent.domain.model.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Boundary verification for sidebar session time grouping: today / yesterday / last 7 days / last 30 days / older by month.
 */
class SessionGroupKeyTest {

    private val zone = ZoneId.systemDefault()

    /** Fixed "current time": 2026-08-14 12:00 (local timezone). */
    private val now = ZonedDateTime.of(2026, 8, 14, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    /** 10 AM, [daysAgo] days before now. */
    private fun at(daysAgo: Long): Long =
        ZonedDateTime.of(2026, 8, 14, 10, 0, 0, 0, zone)
            .minusDays(daysAgo)
            .toInstant()
            .toEpochMilli()

    private fun session(id: String, updatedAt: Long) =
        ChatSession(id = id, title = "t$id", createdAt = 0L, updatedAt = updatedAt)

    @Test
    fun groupKey_updatedToday_isToday() {
        assertEquals("today", sessionGroupKey(at(0), now))
    }

    @Test
    fun groupKey_futureTimestamp_isToday() {
        assertEquals("today", sessionGroupKey(at(-1), now))
    }

    @Test
    fun groupKey_oneDayAgo_isYesterday() {
        assertEquals("yesterday", sessionGroupKey(at(1), now))
    }

    @Test
    fun groupKey_twoToSevenDaysAgo_isLast7Days() {
        assertEquals("7d", sessionGroupKey(at(2), now))
        assertEquals("7d", sessionGroupKey(at(7), now))
    }

    @Test
    fun groupKey_eightToThirtyDaysAgo_isLast30Days() {
        assertEquals("30d", sessionGroupKey(at(8), now))
        assertEquals("30d", sessionGroupKey(at(30), now))
    }

    @Test
    fun groupKey_overThirtyDays_isMonth() {
        assertEquals("2026-07", sessionGroupKey(at(31), now))
        assertEquals("2026-06", sessionGroupKey(at(45), now))
    }

    @Test
    fun groupKey_crossYear_keepsYearMonth() {
        val ts = ZonedDateTime.of(2025, 12, 1, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("2025-12", sessionGroupKey(ts, now))
    }

    @Test
    fun buildGroups_groupsByTimeBucketInOrder() {
        val sessions = listOf(
            session("s1", at(0)), // today
            session("s2", at(1)), // yesterday
            session("s3", at(3)), // within 7 days
            session("s4", at(20)), // within 30 days
            session("s5", at(60)), // month 2026-06
            session("s6", at(90)) // month 2026-05
        )

        val groups = buildSessionGroups(sessions, now)

        assertEquals(
            listOf("today", "yesterday", "7d", "30d", "2026-06", "2026-05"),
            groups.map { it.groupKey }
        )
        assertEquals(listOf("s1"), groups[0].sessions.map { it.id })
        assertEquals(listOf("s6"), groups[5].sessions.map { it.id })
    }

    @Test
    fun buildGroups_sameGroupKeepsSessionOrder() {
        val sessions = listOf(
            session("s1", at(0)),
            session("s2", at(0)),
            session("s3", at(0))
        )

        val groups = buildSessionGroups(sessions, now)

        assertEquals(1, groups.size)
        assertEquals("today", groups[0].groupKey)
        assertEquals(listOf("s1", "s2", "s3"), groups[0].sessions.map { it.id })
    }

    @Test
    fun buildGroups_emptyList_isEmpty() {
        assertEquals(emptyList<SessionGroup>(), buildSessionGroups(emptyList(), now))
    }

    @Test
    fun buildGroups_multiGroup_keepsSessionsInOrder() {
        val sessions = listOf(
            session("s1", at(0)),
            session("s2", at(0)),
            session("s3", at(1))
        )

        val groups = buildSessionGroups(sessions, now)

        assertEquals(listOf("today", "yesterday"), groups.map { it.groupKey })
        assertEquals(listOf("s1", "s2"), groups[0].sessions.map { it.id })
        assertEquals(listOf("s3"), groups[1].sessions.map { it.id })
    }
}
