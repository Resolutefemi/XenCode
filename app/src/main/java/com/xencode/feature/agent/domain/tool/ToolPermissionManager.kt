package com.xencode.feature.agent.domain.tool

import com.xencode.feature.agent.domain.permission.PermissionChoice
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class ToolPermissionManager @Inject constructor() {
    /**
     * All pending permission requests, kept in insertion order (key = request id). With parallel sessions, each session can suspend its own request
     * without blocking the others — that is what lets the sidebar light up every session awaiting permission at once. Within one session, permission requests are handled serially by the workflow,
     * so a session appears at most once in this table.
     */
    private val pending = LinkedHashMap<String, Entry>()
    private val lock = Any()

    /** For dialog display: the single request currently to show (the earliest entered); once resolved it auto-advances to the next. */
    private val _pendingRequest = MutableStateFlow<PendingToolPermission?>(null)
    val pendingRequest: StateFlow<PendingToolPermission?> = _pendingRequest.asStateFlow()

    /** For the sidebar: the set of session ids currently awaiting permission. */
    private val _awaitingSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val awaitingSessionIds: StateFlow<Set<String>> = _awaitingSessionIds.asStateFlow()

    /** Suspends awaiting the user's choice in the dialog (deny/once/always). Different sessions can suspend in parallel without blocking each other. */
    suspend fun awaitApproval(request: PendingToolPermission): PermissionChoice {
        val decision = CompletableDeferred<PermissionChoice>()
        synchronized(lock) {
            pending[request.id] = Entry(request, decision)
            publish()
        }
        try {
            return decision.await()
        } finally {
            synchronized(lock) {
                pending.remove(request.id)
                publish()
            }
        }
    }

    /** The UI reports the user's choice, waking the suspended request with that id. */
    fun resolve(id: String, choice: PermissionChoice) {
        val entry = synchronized(lock) { pending[id] } ?: return
        entry.decision.complete(choice)
    }

    /** Gets a session's current pending request (used by stopAgent to stop a single session, to avoid grabbing another session's dialog). */
    fun pendingForSession(sessionId: String): PendingToolPermission? =
        synchronized(lock) { pending.values.firstOrNull { it.request.sessionId == sessionId }?.request }

    /** Called while holding the lock: projects the internal table to the two public StateFlows. */
    private fun publish() {
        _pendingRequest.value = pending.values.firstOrNull()?.request
        _awaitingSessionIds.value = pending.values.mapNotNull { it.request.sessionId.ifBlank { null } }.toSet()
    }

    private class Entry(
        val request: PendingToolPermission,
        val decision: CompletableDeferred<PermissionChoice>
    )
}
