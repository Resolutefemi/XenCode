package com.xencode.feature.agent.domain.subagent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sub-agent event types. The first four are lifecycle events; the last two are message events that only carry content and never touch the active set.
 */
enum class SubAgentEventType {
    SPAWNED, COMPLETED, FAILED, STOPPED,

    /** A message from the main session to a sub-agent (sender is the parent session). */
    MESSAGE_FROM_PARENT,

    /** A message from a sub-agent to the main session (sender is the sub session). */
    MESSAGE_FROM_SUB
}

/**
 * Sub-agent lifecycle event.
 *
 * @property subSessionId Sub-agent session id.
 * @property parentSessionId Parent session id (parentId in the sub-session record).
 * @property type Event type.
 * @property detail Extra detail: the task instruction for SPAWNED; the sub-agent's final output/error for COMPLETED/FAILED;
 *   the message body for MESSAGE_FROM_PARENT / MESSAGE_FROM_SUB.
 */
data class SubAgentEvent(
    val subSessionId: String,
    val parentSessionId: String,
    val type: SubAgentEventType,
    val detail: String = ""
)

/**
 * Sub-agent event bus: TaskTool emits SPAWNED (sub-agent created), the ViewModel collects it and automatically
 * starts the AI workflow on the sub-session; when that workflow ends, the ViewModel emits COMPLETED/FAILED,
 * and the parent session injects a background notification accordingly (analogous to the terminal's notify=true async callback).
 *
 * Also maintains the set of active sub-agent session ids, for TaskTool's concurrency checks (at most 5 running).
 */
@Singleton
class SubAgentEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<SubAgentEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SubAgentEvent> = _events.asSharedFlow()

    /** The set of currently active (running) sub-agent session ids. */
    private val _activeSubSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val activeSubSessionIds: StateFlow<Set<String>> = _activeSubSessionIds.asStateFlow()

    /** Number of running sub-agents. */
    val activeCount: Int get() = _activeSubSessionIds.value.size

    /** Whether the concurrency limit has been reached. */
    val isFull: Boolean get() = activeCount >= MAX_RUNNING

    /**
     * Directly removes a sub-agent from the active set without broadcasting an event; returns whether it was active before.
     *
     * This path is taken when the user manually stops or deletes a running sub-session from the UI: [emit] cannot be used instead,
     * because the ViewModel would call stopAgentSession again upon the STOPPED event, forming an infinite loop.
     * The return value also distinguishes "user terminated manually" from "already handled by TaskTool" (the latter returns false),
     * avoiding duplicate notification to the parent agent.
     */
    fun release(subSessionId: String): Boolean {
        val current = _activeSubSessionIds.value
        if (subSessionId !in current) return false
        _activeSubSessionIds.value = current - subSessionId
        return true
    }

    fun emit(event: SubAgentEvent) {
        // Maintain the active set synchronously
        when (event.type) {
            SubAgentEventType.SPAWNED -> {
                _activeSubSessionIds.value = _activeSubSessionIds.value + event.subSessionId
            }
            SubAgentEventType.COMPLETED, SubAgentEventType.FAILED, SubAgentEventType.STOPPED -> {
                _activeSubSessionIds.value = _activeSubSessionIds.value - event.subSessionId
            }
            SubAgentEventType.MESSAGE_FROM_PARENT, SubAgentEventType.MESSAGE_FROM_SUB -> {
                // Sending/receiving messages does not change run state; the active set stays unchanged.
            }
        }
        _events.tryEmit(event)
    }

    companion object {
        /** Cap on simultaneously running sub-agents. This is the source of truth; the tool layer only references it instead of keeping its own copy. */
        const val MAX_RUNNING = 5
    }
}