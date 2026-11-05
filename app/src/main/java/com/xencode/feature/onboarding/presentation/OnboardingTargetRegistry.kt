package com.xencode.feature.onboarding.presentation

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.xencode.feature.onboarding.domain.OnboardingStep

/**
 * Registry of onboarding target components: centrally records each step's highlighted component bounds in root coordinates.
 */
class OnboardingTargetRegistry {
    private val _targets = mutableStateMapOf<OnboardingStep, Rect>()
    val targets: Map<OnboardingStep, Rect> get() = _targets

    fun register(step: OnboardingStep, rect: Rect) {
        _targets[step] = rect
    }

    fun unregister(step: OnboardingStep) {
        _targets.remove(step)
    }

    fun targetFor(step: OnboardingStep): Rect? = _targets[step]
}

/**
 * Global CompositionLocal letting deep components report highlight positions transparently.
 */
val LocalOnboardingTargetRegistry = compositionLocalOf<OnboardingTargetRegistry?> { null }

/**
 * Convenience Modifier: tags a target component and auto-reports its coordinate bounds after layout.
 */
fun Modifier.onboardingTarget(step: OnboardingStep): Modifier = composed {
    val registry = LocalOnboardingTargetRegistry.current
    if (registry == null) {
        this
    } else {
        DisposableEffect(step) {
            onDispose {
                registry.unregister(step)
            }
        }
        this.onGloballyPositioned { coordinates ->
            if (coordinates.isAttached) {
                registry.register(step, coordinates.boundsInRoot())
            }
        }
    }
}
