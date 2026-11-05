package com.xencode.feature.agent.domain.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Consumption semantics of the notification queue: peek does not remove, ack removes only the confirmed items, drain takes everything atomically, and sessions do not interfere with each other.
 * These properties guarantee a single background notification is never delivered twice — once piggybacked on a tool result and again by the end-of-turn fallback path.
 */
class AgentNotificationCenterTest {

    private fun task(id: String) = PendingNotification(
        kind = AgentNotificationKind.BACKGROUND_TASK,
        sourceId = id,
        title = "build $id",
        outcome = NotificationOutcome.COMPLETED,
        exitCode = 0
    )

    @Test
    fun peek_doesNotRemove() {
        val center = AgentNotificationCenter()
        center.enqueue("s1", task("term-1"))

        assertEquals(1, center.peek("s1").size)
        assertEquals(1, center.peek("s1").size)
        assertEquals(1, center.pendingCount("s1"))
    }

    @Test
    fun ack_removesOnlyAckedItems() {
        val center = AgentNotificationCenter()
        center.enqueue("s1", task("term-1"))
        val peeked = center.peek("s1")
        // Events arriving after the peek must not be wrongly removed by this ack
        center.enqueue("s1", task("term-2"))

        center.ack("s1", peeked.map { it.seq })

        val remaining = center.peek("s1")
        assertEquals(1, remaining.size)
        assertEquals("term-2", remaining.first().sourceId)
    }

    @Test
    fun drain_takesAllAndClears() {
        val center = AgentNotificationCenter()
        center.enqueue("s1", task("term-1"))
        center.enqueue("s1", task("term-2"))

        val drained = center.drain("s1")

        assertEquals(listOf("term-1", "term-2"), drained.map { it.sourceId })
        assertEquals(0, center.pendingCount("s1"))
        assertTrue(center.drain("s1").isEmpty())
    }

    @Test
    fun sessionsAreIsolated() {
        val center = AgentNotificationCenter()
        center.enqueue("s1", task("term-1"))
        center.enqueue("s2", task("term-2"))

        center.clear("s1")

        assertEquals(0, center.pendingCount("s1"))
        assertEquals(1, center.pendingCount("s2"))
    }

    @Test
    fun clearAll_removesEverySession() {
        val center = AgentNotificationCenter()
        center.enqueue("s1", task("term-1"))
        center.enqueue("s2", task("term-2"))

        center.clearAll()

        assertEquals(0, center.pendingCount("s1"))
        assertEquals(0, center.pendingCount("s2"))
    }
}
