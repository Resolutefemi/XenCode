package com.xencode.feature.onboarding.domain

import com.xencode.feature.onboarding.data.OnboardingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Onboarding coordinator: connects the UI state machine [OnboardingStateHolder] with persistence.
 */
class OnboardingCoordinator(
    private val stateHolder: OnboardingStateHolder,
    private val onPersist: suspend (OnboardingStatus) -> Unit,
    private val scope: CoroutineScope
) {
    /** Launch decision: activates only when not completed / still in progress. */
    fun initialize(status: OnboardingStatus) {
        if (status == OnboardingStatus.IN_PROGRESS) {
            stateHolder.start()
        }
    }

    /** Advances to the next step (or completion). */
    fun nextStep() {
        stateHolder.nextStep()
        val current = stateHolder.state.value
        scope.launch { onPersist(current.status) }
    }

    /** Steps back or jumps to a given step (e.g. back to the trigger button when a dialog closes). */
    fun goToStep(targetStep: OnboardingStep) {
        stateHolder.goToStep(targetStep)
        val current = stateHolder.state.value
        scope.launch { onPersist(current.status) }
    }

    /** The user actively skips onboarding. */
    fun skip() {
        stateHolder.skip()
        scope.launch { onPersist(OnboardingStatus.SKIPPED) }
    }

    /** The user completes onboarding. */
    fun complete() {
        stateHolder.completeNow()
        scope.launch { onPersist(OnboardingStatus.COMPLETED) }
    }

    /** Resets and restarts onboarding (triggered from the settings page). */
    fun resetAndStart() {
        stateHolder.start()
        scope.launch { onPersist(OnboardingStatus.IN_PROGRESS) }
    }
}
