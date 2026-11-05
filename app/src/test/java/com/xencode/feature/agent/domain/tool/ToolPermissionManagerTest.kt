package com.xencode.feature.agent.domain.tool

import com.xencode.feature.agent.domain.permission.PermissionChoice
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-session parallel authorization semantics: different sessions can each suspend their own authorization request simultaneously (no mutual blocking);
 * the sidebar lights up via [ToolPermissionManager.awaitingSessionIds], the dialog shows requests one by one in insertion order,
 * and resolve wakes exactly the right session by request id.
 */
class ToolPermissionManagerTest {

    private fun req(id: String, sessionId: String) = PendingToolPermission(
        id = id,
        toolName = "Bash",
        title = "Confirm tool",
        summary = "",
        details = "",
        argsPreview = "",
        sessionId = sessionId
    )

    @Test
    fun twoSessionsPendSimultaneously_resolveByIdTargetsCorrectSession() = runBlocking {
        val mgr = ToolPermissionManager()

        val d1 = async { mgr.awaitApproval(req("id1", "s1")) }
        val d2 = async { mgr.awaitApproval(req("id2", "s2")) }

        withTimeout(1000) { while (mgr.awaitingSessionIds.value.size < 2) yield() }

        // Both sessions light up, neither blocking the other
        assertEquals(setOf("s1", "s2"), mgr.awaitingSessionIds.value)
        // The dialog shows the earliest-arriving request
        assertEquals("id1", mgr.pendingRequest.value?.id)
        // Query the pending request by session
        assertEquals("id2", mgr.pendingForSession("s2")?.id)

        // Resolve s2 first (out of order), waking only s2
        mgr.resolve("id2", PermissionChoice.ONCE)
        assertEquals(PermissionChoice.ONCE, d2.await())

        withTimeout(1000) { while (mgr.awaitingSessionIds.value != setOf("s1")) yield() }
        assertEquals("id1", mgr.pendingRequest.value?.id)
        assertNull(mgr.pendingForSession("s2"))

        mgr.resolve("id1", PermissionChoice.ALWAYS)
        assertEquals(PermissionChoice.ALWAYS, d1.await())

        assertNull(mgr.pendingRequest.value)
        assertTrue(mgr.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun resolveUnknownId_isNoop() = runBlocking {
        val mgr = ToolPermissionManager()
        val d1 = async { mgr.awaitApproval(req("id1", "s1")) }
        withTimeout(1000) { while (mgr.pendingRequest.value == null) yield() }

        mgr.resolve("nonexistent", PermissionChoice.REJECT)
        // The original request is still pending
        assertTrue(d1.isActive)
        assertEquals("id1", mgr.pendingRequest.value?.id)

        mgr.resolve("id1", PermissionChoice.REJECT)
        assertEquals(PermissionChoice.REJECT, d1.await())
    }
}
