package com.xencode.feature.agent.domain.tool.mode

import com.xencode.feature.agent.data.local.dao.ChatSessionDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages the suspend/resume flow of plan review:
 * when the AI switches from PLAN mode back to BUILD mode, the workflow calls [awaitApproval] to suspend,
 * resuming once the plan-review panel in the UI receives the user's decision.
 */
@Singleton
class PlanApprovalManager @Inject constructor(
    private val chatSessionDao: ChatSessionDao
) {
    private val _pendingApproval = MutableStateFlow<PlanApprovalRequest?>(null)
    val pendingApproval: StateFlow<PlanApprovalRequest?> = _pendingApproval.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var currentDecision: CompletableDeferred<PlanApprovalChoice>? = null
    private var currentSessionId: String? = null

    /** Suspends waiting for the user's decision in the plan-review panel. */
    suspend fun awaitApproval(reason: String, sessionId: String? = null): PlanApprovalChoice {
        val decision = CompletableDeferred<PlanApprovalChoice>()
        currentDecision = decision
        currentSessionId = sessionId
        _pendingApproval.value = PlanApprovalRequest(reason = reason)

        try {
            return decision.await()
        } catch (e: CancellationException) {
            // The user did not approve the switch (canceled via "Stop" or similar): PlanModeTool already wrote the target mode to the DB,
            // and cancellation means it was not approved, so roll back — otherwise the mode would switch without approval.
            rollbackModeToPlan(sessionId)
            throw e
        } finally {
            currentDecision = null
            currentSessionId = null
            _pendingApproval.value = null
        }
    }

    /** The UI reports the user's choice, waking the suspended [awaitApproval]. */
    fun resolve(choice: PlanApprovalChoice) {
        _pendingApproval.value = null

        // If the user declines, roll the mode in the database back to PLAN (PlanModeTool already wrote the target mode)
        if (choice == PlanApprovalChoice.REFINE) {
            rollbackModeToPlan(currentSessionId)
        }

        currentDecision?.complete(choice)
    }

    /** Rolls the session mode back to PLAN (PlanModeTool wrote the target mode up front; restore it when unapproved). */
    private fun rollbackModeToPlan(sessionId: String?) {
        val sid = sessionId ?: return
        scope.launch {
            val entity = chatSessionDao.getById(sid)
            if (entity != null && entity.mode != "PLAN") {
                chatSessionDao.upsert(entity.copy(mode = "PLAN"))
            }
        }
    }
}

data class PlanApprovalRequest(val reason: String)

enum class PlanApprovalChoice {
    /** Approve the plan and switch to BUILD mode for execution */
    APPROVE,
    /** Decline and stay in PLAN mode for further feedback */
    REFINE
}
