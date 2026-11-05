package com.xencode.feature.onboarding.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.xencode.feature.onboarding.domain.OnboardingCoordinator
import com.xencode.feature.onboarding.domain.OnboardingStateHolder

/**
 * Master overlay of the spotlight onboarding (floats above every page).
 */
@Composable
fun OnboardingOverlay(
    stateHolder: OnboardingStateHolder,
    coordinator: OnboardingCoordinator,
    registry: OnboardingTargetRegistry,
    modifier: Modifier = Modifier,
    onNextStep: (() -> Unit)? = null,
    onTargetClick: (() -> Unit)? = null
) {
    val state by stateHolder.state.collectAsState()
    if (!state.active) return

    val currentStep = state.step
    val targetRect = registry.targetFor(currentStep)

    SpotlightOverlay(
        currentStep = currentStep,
        targetRect = targetRect,
        onNext = { onNextStep?.invoke() ?: coordinator.nextStep() },
        onSkip = { coordinator.skip() },
        onTargetClick = onTargetClick,
        modifier = modifier
    )
}
