package com.xencode.feature.agent.domain.notification

import com.xencode.feature.agent.domain.model.AgentMode
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Source kinds of pending notifications. */
enum class AgentNotificationKind { BACKGROUND_TASK, SUBAGENT, AGENT_MESSAGE, MODE_CHANGE, USER_MESSAGE }

/**
 * How an async task ended. [STOPPED] and [FAILED] must stay distinct: a manual stop by the user is not an execution error,
 * and conflating them would make the AI treat a deliberate interruption as a fault to debug or retry.
 */
enum class NotificationOutcome { COMPLETED, FAILED, STOPPED }

/**
 * One async-completion notification pending delivery to the AI (background terminal command / subagent).
 *
 * @property sourceId Terminal tabId or subagent session id; the AI uses it to call terminal(read) / task(read) for the full result.
 * @property detail Extra detail on how it ended: the error for a failed subagent, an explanation when stopped; null on normal completion.
 * @property message Body of [AgentNotificationKind.AGENT_MESSAGE] / [AgentNotificationKind.USER_MESSAGE].
 * @property fromParent Direction of [AgentNotificationKind.AGENT_MESSAGE]: true means the sender is the main session (recipient is the subagent),
 *   false means the sender is the subagent (recipient is the main session). The Formatter uses it to build the matching reply hint.
 * @property newMode Target mode of [AgentNotificationKind.MODE_CHANGE]: the new mode after the user switched mid-work.
 * @property seq Monotonic sequence number assigned by [AgentNotificationCenter] for precise ack after peek; 0 while not enqueued.
 */
data class PendingNotification(
    val kind: AgentNotificationKind,
    val sourceId: String,
    val title: String,
    val outcome: NotificationOutcome,
    val command: String? = null,
    val exitCode: Int? = null,
    val tailOutput: String? = null,
    val detail: String? = null,
    val message: String? = null,
    val fromParent: Boolean = false,
    val newMode: AgentMode? = null,
    val seq: Long = 0
)

/**
 * Per-session pending notification queue: background-task and subagent completion events enqueue while the AI is busy, then deliver via one of two paths.
 *
 * - **Piggyback** (preferred): after each batch of tool executions, [StatefulAgentWorkflow][com.xencode.feature.agent.domain.workflow.StatefulAgentWorkflow]
 *   peeks notifications and injects them into tool results, delivering within the current turn and saving one "end this turn then start another" LLM round trip.
 * - **Fallback**: when the AI called no tools for a whole turn, the ViewModel [drain]s in the turn's finally and triggers a new turn as a system notification message.
 *
 * Enqueueing happens on the main thread (ViewModel collecting Flow events), peek/ack on the IO coroutines running tools, hence the locking on all operations.
 * [peek] and [ack] are two steps rather than take-once: a notification counts as delivered only after its tool result is actually persisted (ToolCallFinished emitted);
 * if cancelled midway it stays in the queue for the next tool batch or the fallback path.
 */
@Singleton
class AgentNotificationCenter @Inject constructor() {

    private val lock = Any()
    private val pending = mutableMapOf<String, MutableList<PendingNotification>>()
    private val seqGenerator = AtomicLong(0)

    /** Enqueue one notification and return its assigned sequence number. */
    fun enqueue(sessionId: String, item: PendingNotification): Long {
        val seq = seqGenerator.incrementAndGet()
        synchronized(lock) {
            pending.getOrPut(sessionId) { mutableListOf() }.add(item.copy(seq = seq))
        }
        return seq
    }

    /** Read all currently pending notifications of the session (without removing them). */
    fun peek(sessionId: String): List<PendingNotification> = synchronized(lock) {
        pending[sessionId]?.toList() ?: emptyList()
    }

    /** Acknowledge delivery: remove by sequence number; entries enqueued after peek are unaffected. */
    fun ack(sessionId: String, seqs: Collection<Long>) {
        if (seqs.isEmpty()) return
        synchronized(lock) {
            val list = pending[sessionId] ?: return
            list.removeAll { it.seq in seqs }
            if (list.isEmpty()) pending.remove(sessionId)
        }
    }

    /** Atomically take all pending notifications of the session. */
    fun drain(sessionId: String): List<PendingNotification> = synchronized(lock) {
        pending.remove(sessionId) ?: emptyList()
    }

    fun pendingCount(sessionId: String): Int = synchronized(lock) {
        pending[sessionId]?.size ?: 0
    }

    fun clear(sessionId: String) {
        synchronized(lock) { pending.remove(sessionId) }
    }

    fun clearAll() {
        synchronized(lock) { pending.clear() }
    }
}
