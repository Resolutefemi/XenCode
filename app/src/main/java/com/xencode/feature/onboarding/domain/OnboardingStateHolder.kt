package com.xencode.feature.onboarding.domain

import com.xencode.feature.onboarding.data.OnboardingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI runtime state of the first-launch spotlight onboarding.
 */
data class OnboardingUiState(
    val active: Boolean = false,
    val step: OnboardingStep = OnboardingStep.OPEN_SIDEBAR,
    val status: OnboardingStatus = OnboardingStatus.IN_PROGRESS
)

/**
 * Onboarding state machine (pure in-memory state; cross-process persistence is handled by OnboardingRepository).
 */
class OnboardingStateHolder {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /** Starts onboarding from the first step [OnboardingStep.OPEN_SIDEBAR]. */
    fun start() {
        _state.value = OnboardingUiState(
            active = true,
            step = OnboardingStep.OPEN_SIDEBAR,
            status = OnboardingStatus.IN_PROGRESS
        )
    }

    /** Advances to the next step; completes onboarding if this is the last one. */
    fun nextStep() {
        if (!_state.value.active) return
        val current = _state.value.step
        val next = current.next()
        if (next == null) {
            completeNow()
        } else {
            _state.value = _state.value.copy(step = next)
        }
    }

    /** Steps back or jumps to a given onboarding step. */
    fun goToStep(targetStep: OnboardingStep) {
        if (!_state.value.active) return
        _state.value = _state.value.copy(step = targetStep)
    }

    /** Skips onboarding. */
    fun skip() {
        _state.value = OnboardingUiState(
            active = false,
            step = _state.value.step,
            status = OnboardingStatus.SKIPPED
        )
    }

    /** Completes onboarding. */
    fun completeNow() {
        _state.value = OnboardingUiState(
            active = false,
            step = _state.value.step,
            status = OnboardingStatus.COMPLETED
        )
    }

    /** Whether onboarding is currently active. */
    val isActive: Boolean get() = _state.value.active
}

/** Returns the step after the current one, or null if this is the last step. */
internal fun OnboardingStep.next(): OnboardingStep? {
    val nextIndex = ordinal + 1
    return if (nextIndex < OnboardingStep.all.size) OnboardingStep.all[nextIndex] else null
}
