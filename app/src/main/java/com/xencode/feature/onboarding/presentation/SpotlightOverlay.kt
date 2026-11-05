package com.xencode.feature.onboarding.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xencode.R
import com.xencode.core.theme.Radius
import com.xencode.core.theme.Spacing
import com.xencode.core.theme.semanticColors
import com.xencode.feature.onboarding.domain.OnboardingStep
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Full-screen spotlight onboarding scrim (Spotlight Overlay).
 *
 * 1. System-styled translucent scrim (40% light / 52% dark) that never feels oppressive;
 * 2. Hardware-accelerated rounded-corner cutout (BlendMode.Clear) + smooth in-place fade-in (never slides from the screen center to the target);
 * 3. The focused target's edge carries a very delicate breathing glow border;
 * 4. Simulated panels use the native [ModelLogoIcon] and app-level card layering, shedding the cartoon-toy look;
 * 5. A floating, refined Material 3 instruction card - no heavy black shadows, with a smooth breathing fade-in transition.
 */
@Composable
fun SpotlightOverlay(
    currentStep: OnboardingStep,
    targetRect: Rect?,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onTargetClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val cardMarginPx = with(density) { 16.dp.toPx() }
    val cardGapPx = with(density) { 12.dp.toPx() }

    // Breathing animation: a soft glow pulsing around the highlight stroke
    val infiniteTransition = rememberInfiniteTransition(label = "SpotlightPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.50f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidthPx = constraints.maxWidth.toFloat()
        val screenHeightPx = constraints.maxHeight.toFloat()

        val isSheetStep = currentStep == OnboardingStep.SIMULATE_FETCH_DIALOG ||
            currentStep == OnboardingStep.SIMULATE_CHOOSE_MODEL

        // Adaptively computes the best focus size and safe margins for the target's shape (small icon button vs wide component), eliminating off-screen clipping
        val (optimalTargetRect, holeRadiusPx) = remember(targetRect, screenWidthPx, screenHeightPx, density, isSheetStep) {
            if (targetRect != null && !isSheetStep) {
                calculateOptimalHoleBounds(targetRect, screenWidthPx, screenHeightPx, density)
            } else {
                Rect.Zero to with(density) { 12.dp.toPx() }
            }
        }

        // Spotlight position and alpha state machine
        val holeLeft = remember { Animatable(0f) }
        val holeTop = remember { Animatable(0f) }
        val holeRight = remember { Animatable(0f) }
        val holeBottom = remember { Animatable(0f) }
        val spotlightAlpha = remember { Animatable(0f) }
        var isFirstTarget by remember { mutableStateOf(true) }
        var lastTargetRect by remember { mutableStateOf<Rect?>(null) }

        LaunchedEffect(optimalTargetRect, targetRect, isSheetStep) {
            if (isSheetStep || targetRect == null) {
                spotlightAlpha.animateTo(0f, animationSpec = tween(200))
            } else {
                val last = lastTargetRect
                val isBigJump = last == null ||
                    hypot(
                        (optimalTargetRect.center.x - last.center.x).toDouble(),
                        (optimalTargetRect.center.y - last.center.y).toDouble()
                    ) > with(density) { 240.dp.toPx() }

                if (isFirstTarget || isBigJump) {
                    // First appearance or a big cross-page jump:
                    // snap to the new target position and fade in there (removing the odd full-screen diagonal drag animation)
                    holeLeft.snapTo(optimalTargetRect.left)
                    holeTop.snapTo(optimalTargetRect.top)
                    holeRight.snapTo(optimalTargetRect.right)
                    holeBottom.snapTo(optimalTargetRect.bottom)
                    isFirstTarget = false
                    spotlightAlpha.animateTo(1f, animationSpec = tween(260, easing = FastOutSlowInEasing))
                } else {
                    // A short hop within the same area: transition smoothly into place
                    launch { holeLeft.animateTo(optimalTargetRect.left, tween(300, easing = FastOutSlowInEasing)) }
                    launch { holeTop.animateTo(optimalTargetRect.top, tween(300, easing = FastOutSlowInEasing)) }
                    launch { holeRight.animateTo(optimalTargetRect.right, tween(300, easing = FastOutSlowInEasing)) }
                    launch { holeBottom.animateTo(optimalTargetRect.bottom, tween(300, easing = FastOutSlowInEasing)) }
                    if (spotlightAlpha.value < 1f) {
                        launch { spotlightAlpha.animateTo(1f, tween(200)) }
                    }
                }
                lastTargetRect = optimalTargetRect
            }
        }

        val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
        val scrimColor = if (isLight) {
            Color.Black.copy(alpha = 0.40f)
        } else {
            Color.Black.copy(alpha = 0.52f)
        }

        val primaryColor = MaterialTheme.colorScheme.primary

        // 1. Full-screen Canvas: soft translucent scrim + cutout clear + highlight glow border
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .pointerInput(targetRect, optimalTargetRect, onTargetClick) {
                    detectTapGestures { offset ->
                        // If the tap lands inside the current highlight, respond to the highlighted target first
                        if (targetRect != null && spotlightAlpha.value > 0.3f) {
                            val inHole = offset.x in optimalTargetRect.left..optimalTargetRect.right &&
                                offset.y in optimalTargetRect.top..optimalTargetRect.bottom
                            if (inHole) {
                                onTargetClick?.invoke() ?: onNext()
                                return@detectTapGestures
                            }
                        }
                        // Consume taps outside the scrim to prevent accidental touches on elements behind
                    }
                }
        ) {
            // Translucent scrim
            drawRect(color = scrimColor)

            if (spotlightAlpha.value > 0f) {
                val currentAlpha = spotlightAlpha.value
                val activeHoleLeft = holeLeft.value
                val activeHoleTop = holeTop.value
                val activeHoleWidth = holeRight.value - holeLeft.value
                val activeHoleHeight = holeBottom.value - holeTop.value

                if (activeHoleWidth > 0f && activeHoleHeight > 0f) {
                    // Cutout
                    drawRoundRect(
                        color = Color.Transparent,
                        topLeft = Offset(activeHoleLeft, activeHoleTop),
                        size = Size(activeHoleWidth, activeHoleHeight),
                        cornerRadius = CornerRadius(holeRadiusPx, holeRadiusPx),
                        blendMode = BlendMode.Clear
                    )
                    // Highlight focus outer border (solid glow)
                    drawRoundRect(
                        color = primaryColor.copy(alpha = pulseAlpha * currentAlpha),
                        topLeft = Offset(activeHoleLeft, activeHoleTop),
                        size = Size(activeHoleWidth, activeHoleHeight),
                        cornerRadius = CornerRadius(holeRadiusPx, holeRadiusPx),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    // Outer halo (twice the glow radius)
                    val glowPadding = 2.dp.toPx()
                    drawRoundRect(
                        color = primaryColor.copy(alpha = 0.20f * pulseAlpha * currentAlpha),
                        topLeft = Offset(activeHoleLeft - glowPadding, activeHoleTop - glowPadding),
                        size = Size(activeHoleWidth + glowPadding * 2, activeHoleHeight + glowPadding * 2),
                        cornerRadius = CornerRadius(holeRadiusPx + glowPadding, holeRadiusPx + glowPadding),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
        }

        // 2. Instruction card: anchored precisely by its measured height, never overlapping the spotlight
        val cardWidthDp = 310.dp
        val cardWidthPx = with(density) { cardWidthDp.toPx() }
        var actualCardHeightPx by remember { mutableFloatStateOf(with(density) { 210.dp.toPx() }) }

        val targetCenterX = if (targetRect != null && spotlightAlpha.value > 0.1f) {
            (holeLeft.value + holeRight.value) / 2f
        } else {
            screenWidthPx * 0.5f
        }

        val cardLeftPx = if (isSheetStep) {
            (screenWidthPx - cardWidthPx) / 2f
        } else {
            (targetCenterX - cardWidthPx / 2f).coerceIn(
                cardMarginPx,
                (screenWidthPx - cardWidthPx - cardMarginPx).coerceAtLeast(cardMarginPx)
            )
        }

        val isBottomHalf = targetRect != null && ((holeTop.value + holeBottom.value) / 2f > screenHeightPx * 0.52f)
        val cardTopPx = if (isSheetStep) {
            // Bottom-sheet steps: the sheet takes the lower half, so the card floats centered in the upper half's safe area, never covered by the sheet
            (screenHeightPx * 0.10f).coerceIn(
                cardMarginPx + with(density) { 36.dp.toPx() },
                (screenHeightPx * 0.38f - actualCardHeightPx).coerceAtLeast(cardMarginPx + with(density) { 36.dp.toPx() })
            )
        } else if (targetRect == null || spotlightAlpha.value < 0.1f) {
            screenHeightPx * 0.38f
        } else if (isBottomHalf) {
            // Target in the lower half: the card sits above the target, its bottom edge keeping exactly cardGapPx from the highlight's top
            (holeTop.value - cardGapPx - actualCardHeightPx).coerceAtLeast(cardMarginPx)
        } else {
            // Target in the upper half: the card sits below the target, its top edge keeping exactly cardGapPx from the highlight's bottom
            (holeBottom.value + cardGapPx).coerceAtMost(screenHeightPx - actualCardHeightPx - cardMarginPx)
        }

        val animCardX by animateFloatAsState(
            targetValue = cardLeftPx,
            animationSpec = tween(280, easing = FastOutSlowInEasing),
            label = "CardX"
        )
        val animCardY by animateFloatAsState(
            targetValue = cardTopPx,
            animationSpec = tween(280, easing = FastOutSlowInEasing),
            label = "CardY"
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(animCardX.roundToInt(), animCardY.roundToInt()) }
                .onSizeChanged { size ->
                    if (size.height > 0) {
                        actualCardHeightPx = size.height.toFloat()
                    }
                }
        ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    (fadeIn(tween(200, delayMillis = 40)) + slideInVertically(tween(200)) { it / 8 })
                        .togetherWith(fadeOut(tween(130)))
                },
                label = "CardContentTransition"
            ) { step ->
                SpotlightCard(
                    step = step,
                    onNext = onNext,
                    onSkip = onSkip,
                    modifier = Modifier.widthIn(max = cardWidthDp)
                )
            }
        }
    }
}

/**
 * A high-quality instruction card matching the app-dev-tool style.
 */
@Composable
private fun SpotlightCard(
    step: OnboardingStep,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.semanticColors.cardSurface,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
        ) {
            // Step badge: a minimal pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(Radius.pill),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                ) {
                    Text(
                        text = stringResource(
                            R.string.onboarding_step_badge,
                            step.stepIndex,
                            OnboardingStep.totalSteps
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            // Step title
            Text(
                text = stringResource(step.titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(Spacing.xs))

            // Step description
            Text(
                text = stringResource(step.descRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(Spacing.lg))

            // Bottom action row: skip + next/finish
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onSkip,
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_skip),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Button(
                    onClick = onNext,
                    shape = RoundedCornerShape(Radius.md),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.height(38.dp),
                    contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(if (step.isLastStep) R.string.onboarding_done else R.string.onboarding_next),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * Computes the optimal focus cutout from the target element's root-view bounds, screen edges, and component shape.
 *
 * 1. Compact small targets (e.g. top-bar IconButton, model-switch key): focus symmetrically on the visual
 *    center, avoiding big squares clipped at the walls caused by 48dp touch targets plus padding;
 * 2. Wide large targets (input fields, settings rows): keep the surrounding padding and clamp against screen-edge overflow;
 * 3. A safe margin from the screen edge [minEdgeMarginPx] keeps corners and breathing strokes 100% fully visible, never clipped.
 */
private fun calculateOptimalHoleBounds(
    target: Rect,
    screenWidth: Float,
    screenHeight: Float,
    density: androidx.compose.ui.unit.Density
): Pair<Rect, Float> {
    val smallTargetThresholdPx = with(density) { 56.dp.toPx() }
    val minEdgeMarginPx = with(density) { 6.dp.toPx() }

    val isSmallTarget = target.width <= smallTargetThresholdPx && target.height <= smallTargetThresholdPx

    return if (isSmallTarget) {
        val targetSizePx = with(density) { 42.dp.toPx() }
        val halfSize = targetSizePx / 2f
        val centerX = target.center.x
        val centerY = target.center.y

        // Keep within screen edges, preserving minEdgeMarginPx
        val left = (centerX - halfSize).coerceIn(minEdgeMarginPx, (screenWidth - minEdgeMarginPx - targetSizePx).coerceAtLeast(minEdgeMarginPx))
        val top = (centerY - halfSize).coerceIn(minEdgeMarginPx, (screenHeight - minEdgeMarginPx - targetSizePx).coerceAtLeast(minEdgeMarginPx))
        val rect = Rect(left, top, left + targetSizePx, top + targetSizePx)
        val cornerRadiusPx = with(density) { 10.dp.toPx() }
        rect to cornerRadiusPx
    } else {
        val paddingPx = with(density) { 4.dp.toPx() }
        val left = (target.left - paddingPx).coerceAtLeast(minEdgeMarginPx)
        val top = (target.top - paddingPx).coerceAtLeast(minEdgeMarginPx)
        val right = (target.right + paddingPx).coerceAtMost(screenWidth - minEdgeMarginPx).coerceAtLeast(left)
        val bottom = (target.bottom + paddingPx).coerceAtMost(screenHeight - minEdgeMarginPx).coerceAtLeast(top)
        val rect = Rect(left, top, right, bottom)
        val cornerRadiusPx = with(density) { 12.dp.toPx() }
        rect to cornerRadiusPx
    }
}
