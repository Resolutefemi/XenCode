package com.xencode.core.ui

import android.os.Build
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Bottom padding that follows the soft keyboard frame by frame (used to keep bottom bars /
 * action bars above the keyboard).
 * Takes the larger of ime and navigationBars: floating above the keyboard when it's shown,
 * resting on the navigation bar when hidden, with no doubled padding.
 *
 * Why go to all this trouble: the app locks `targetSdk = 28` (PRoot must execute from a
 * writable directory, see build.gradle.kts). Below targetSdk 30,
 * `windowSoftInputMode=adjustResize` takes the legacy compatibility-resize path, which
 * **swallows** the platform's per-frame IME inset animation — `WindowInsets.ime` jumps to its
 * final value only after the keyboard animation finishes. The visible result: the keyboard
 * pops up first, then the bottom bar trails a beat behind.
 *
 * Fix: on API 30+, Activity.onCreate switches the window to `ADJUST_NOTHING` (yielding to
 * the legacy resize path) and we push the bottom bar up with insets ourselves; this function
 * also registers a [WindowInsetsAnimationCompat] callback that reads the keyboard's
 * **current-frame** position in onProgress to drive the padding, moving in lockstep with the
 * keyboard.
 *
 * **Important**: softInputMode is set once by the Activity; this function's onDispose does
 * not restore it — when multiple screens (Terminal / Chat) use this function simultaneously,
 * the two composables coexist during NavHost transitions, and disposing the old screen would
 * clear the new screen's just-registered callback and flip softInputMode back to
 * adjustResize, triggering a window relayout and a white flash.
 *
 * Below API 30 there is no IME inset animation API; we keep the manifest's legacy
 * adjustResize behavior (the window resizes — functional, just without the smooth
 * animation).
 */
@Composable
fun rememberImeBottomInset(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current

    // Resting / final state: after the keyboard animation ends, on rotation, and on the first
    // frame, Compose insets give the accurate value.
    val restingPx = maxOf(
        WindowInsets.ime.getBottom(density),
        WindowInsets.navigationBars.getBottom(density)
    )

    // While animating, onProgress supplies per-frame values.
    var progressPx by remember { mutableIntStateOf(restingPx) }
    var animating by remember { mutableStateOf(false) }

    if (Build.VERSION.SDK_INT >= 30) {
        DisposableEffect(view) {
            val callback = object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) animating = true
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                    progressPx = maxOf(ime, nav)
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) animating = false
                }
            }
            ViewCompat.setWindowInsetsAnimationCallback(view, callback)

            onDispose { }
        }
    }

    // Uncontrolled keyboard-show paths (e.g. the terminal triggering the soft keyboard via
    // InputMethodManager.showSoftInput) get no per-frame onProgress callbacks; restingPx jumps
    // to the final value in one step once WindowInsets.ime settles, so the bottom bar
    // "teleports". On API 30+ we tween this resting value so the bar slides smoothly; the
    // controlled path (Compose TextField) has per-frame callbacks, sets animating=true, and
    // goes straight to progressPx with no second animation layered on.
    // Below API 30 no extra adaptation: keep the manifest's adjustResize system default and
    // leave older devices as they were.
    val animatedRestingPx by animateIntAsState(
        targetValue = restingPx,
        animationSpec = tween(durationMillis = 220),
        label = "ime-resting"
    )
    val px = when {
        animating -> progressPx
        Build.VERSION.SDK_INT >= 30 -> animatedRestingPx
        else -> restingPx
    }
    return with(density) { px.toDp() }
}