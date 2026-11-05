package com.xencode.feature.agent.domain.telemetry

import com.xencode.feature.agent.data.local.dao.XpEventDao
import com.xencode.feature.agent.data.local.entity.XpEventEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app-side XP ledger writer/reader. Award rules live in [XpTelemetry] (pure);
 * this class only persists rows and derives summaries.
 *
 * All public methods are suspend and safe to call from any coroutine; the ledger is
 * append-only, so concurrent awards just produce more rows.
 */
@Singleton
class XpRecorder @Inject constructor(
    private val dao: XpEventDao
) {
    /** A finished agent turn in [sessionId] (not awarded for subagent sessions). */
    suspend fun recordTurnCompleted(sessionId: String, label: String, now: Long = System.currentTimeMillis()) {
        dao.insert(
            XpEventEntity(
                sessionId = sessionId,
                kind = XpTelemetry.Kind.TURN_COMPLETED.name,
                amount = XpTelemetry.turnAward(),
                label = label,
                createdAt = now
            )
        )
        maybeAwardStreakBonus(sessionId, now)
    }

    /** A goal marked DONE via /goal; [goalTitle] goes into the event label. */
    suspend fun recordGoalCompleted(sessionId: String, goalTitle: String, now: Long = System.currentTimeMillis()) {
        dao.insert(
            XpEventEntity(
                sessionId = sessionId,
                kind = XpTelemetry.Kind.GOAL_COMPLETED.name,
                amount = XpTelemetry.goalAward(goalTitle),
                label = goalTitle,
                createdAt = now
            )
        )
        maybeAwardStreakBonus(sessionId, now)
    }

    /**
     * First award of a given day also pays the streak bonus for the running streak.
     * "First of the day" is checked cheaply: any event already existing today (>= day start)
     * means the bonus was already considered.
     */
    private suspend fun maybeAwardStreakBonus(sessionId: String, now: Long) {
        val dayStart = now - (now % XpTelemetry.MILLIS_PER_DAY)
        val todayEvents = dao.getSince(dayStart)
        if (todayEvents.isNotEmpty()) return

        val all = dao.getAllOnce()
        val dayEpochs = all.mapTo(mutableSetOf()) { XpTelemetry.epochDay(it.createdAt) }
        val streak = XpTelemetry.currentStreak(dayEpochs, XpTelemetry.epochDay(now))
        val bonus = XpTelemetry.dailyStreakBonus(streak)
        if (bonus > 0) {
            dao.insert(
                XpEventEntity(
                    sessionId = sessionId,
                    kind = XpTelemetry.Kind.DAILY_STREAK_BONUS.name,
                    amount = bonus,
                    label = "${streak}d streak kept alive",
                    createdAt = now
                )
            )
        }
    }

    /** Aggregate view for /xp. */
    suspend fun summary(): XpSummary {
        val all = dao.getAllOnce()
        val total = all.sumOf { it.amount }
        val dayEpochs = all.mapTo(mutableSetOf()) { XpTelemetry.epochDay(it.createdAt) }
        val now = System.currentTimeMillis()
        val streak = XpTelemetry.currentStreak(dayEpochs, XpTelemetry.epochDay(now))
        val dayStart = now - (now % XpTelemetry.MILLIS_PER_DAY)
        val today = all.filter { it.createdAt >= dayStart }.sumOf { it.amount }
        return XpSummary(
            totalXp = total,
            level = XpTelemetry.levelFor(total),
            nextLevelXp = XpTelemetry.nextLevelXp(XpTelemetry.levelFor(total)),
            streakDays = streak,
            todayXp = today,
            recent = all.takeLast(RECENT_LIMIT)
        )
    }

    companion object {
        const val RECENT_LIMIT = 8
    }
}

/** Derived, render-ready stats. */
data class XpSummary(
    val totalXp: Long,
    val level: Int,
    val nextLevelXp: Long,
    val streakDays: Int,
    val todayXp: Long,
    val recent: List<XpEventEntity>
)
