package com.xencode.core.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset

/**
 * Page transition spec (Material "shared axis X"): the two pages move **in the same
 * direction** — pushed left together when going forward, pulled right together when going
 * back — paired with staggered fade-out/fade-in. Top-level routes (NavHost) and the settings
 * screen's internal sections share this one spec, so the two places don't each define their
 * own parameters with mismatched animation rhythm.
 *
 * Why a small same-direction slide instead of the iOS style of "new page covering the old /
 * old page sliding away to reveal the layer beneath": in `AnimatedContent` the **incoming
 * content is always placed on top** (with equal zIndex, target is placed last), and
 * navigation-compose's NavHost doesn't expose `targetContentZIndex`, so on back navigation
 * there's no way to place the popped page on top. A same-direction slide doesn't depend on
 * z-order, so in either direction the page that should be revealed is never covered.
 *
 * The fade-out is faster than the fade-in and the fade-in is delayed a bit, so the two pages
 * are almost never visible at once, avoiding muddy blended colors from the two layers during
 * the overlap.
 */
const val PAGE_MOTION_MS = 260

private const val PAGE_FADE_IN_MS = 200
private const val PAGE_FADE_IN_DELAY_MS = 60
private const val PAGE_FADE_OUT_MS = 90

/** Slide distance as a fraction of container width. A full-screen shift feels like flinging the page on large screens; a quarter is enough to convey direction. */
private const val PAGE_SLIDE_FRACTION = 0.25f

private val PageSlideSpec = tween<IntOffset>(
    durationMillis = PAGE_MOTION_MS,
    easing = FastOutSlowInEasing
)

/**
 * Enter transition: with [forward] = true (going one level deeper) it slides in from the
 * right; false (going back one level) slides in from the left.
 */
fun pageEnter(forward: Boolean): EnterTransition =
    slideInHorizontally(PageSlideSpec) { width ->
        val offset = (width * PAGE_SLIDE_FRACTION).toInt()
        if (forward) offset else -offset
    } + fadeIn(tween(PAGE_FADE_IN_MS, delayMillis = PAGE_FADE_IN_DELAY_MS))

/** Exit transition: same direction as [pageEnter] — exits left when going forward, right when going back. */
fun pageExit(forward: Boolean): ExitTransition =
    slideOutHorizontally(PageSlideSpec) { width ->
        val offset = (width * PAGE_SLIDE_FRACTION).toInt()
        if (forward) -offset else offset
    } + fadeOut(tween(PAGE_FADE_OUT_MS))
