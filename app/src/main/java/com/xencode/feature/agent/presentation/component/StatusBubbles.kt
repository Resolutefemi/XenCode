package com.xencode.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xencode.R
import com.xencode.core.theme.Brand
import com.xencode.core.theme.Spacing
import com.xencode.feature.agent.domain.provider.RetryErrorInfo
import com.xencode.feature.agent.domain.provider.RetryErrorKind
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import com.xencode.core.ui.ExpandableChevronIcon
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Key
import kotlinx.coroutines.delay

/** Period of one full ripple-highlight sweep (ms). */
private const val RIPPLE_PERIOD_MS = 1600

/** Highlight band width as a ratio of content width: too wide looks like the whole block changing color, too narrow is imperceptible. */
private const val RIPPLE_BAND_RATIO = 0.5f

/** Opacity at the highlight band's edges (both gradient ends). */
private const val RIPPLE_EDGE_ALPHA = 0.12f

/**
 * Peak opacity of the highlight band.
 *
 * The highlight is white (the mainstream approach): in dark themes it "brightens" the glyphs; in light themes it lifts them toward the background as a sheen.
 * The peak must not be too high — white at 0.85 in a light theme erases the glyphs outright; around 0.5 it reads as "a flash of light, still legible".
 */
private const val RIPPLE_PEAK_ALPHA = 0.5f

/**
 * Ripple highlight: a band that is bright in the middle and transparent at both ends sweeps left to right periodically.
 *
 * Drawn over the already-drawn content with [BlendMode.SrcAtop] — it only affects opaque pixels, so it never paints
 * a colored strip over blank areas. The highlight defaults to white (the Claude Code / mainstream shimmer approach); in light themes, white lifts
 * the glyphs toward the background on text pixels, forming a gliding sheen rather than laying a gray veil on top.
 *
 * Performance: progress is read during the draw phase of [drawWithContent] (the `by` delegate reads inside the lambda), redraw-only, no recomposition.
 */
@Composable
internal fun Modifier.rippleHighlight(
    highlight: Color = Color.White,
    bandRatio: Float = RIPPLE_BAND_RATIO,
    periodMs: Int = RIPPLE_PERIOD_MS
): Modifier {
    val transition = rememberInfiniteTransition(label = "ripple-highlight")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple-progress"
    )
    return drawWithContent {
        drawContent()
        val band = size.width * bandRatio
        // Start fully moved out on the left and end fully moved out on the right, leaving one band of margin at each end for a sense of "pausing"
        val center = -band + progress * (size.width + 2 * band)
        drawRect(
            brush = Brush.linearGradient(
                // Five-stop gradient: fully transparent at both ends with a soft peak in the middle, so a sweep reads as "a flash" rather than a hard-edged color band
                colors = listOf(
                    Color.Transparent,
                    highlight.copy(alpha = RIPPLE_EDGE_ALPHA),
                    highlight.copy(alpha = RIPPLE_PEAK_ALPHA),
                    highlight.copy(alpha = RIPPLE_EDGE_ALPHA),
                    Color.Transparent
                ),
                start = Offset(center - band / 2f, 0f),
                end = Offset(center + band / 2f, 0f)
            ),
            blendMode = BlendMode.SrcAtop
        )
    }
}

/**
 * A piece of text with the ripple highlight running across it (the text version of [Modifier.rippleHighlight]).
 *
 * The ripple is drawn across **the text's own width**: callers must not give it `weight`, or the band will crawl across
 * the entire allocatable width and these few characters get swept for only an instant (let the outer container take the space instead).
 *
 * Passing null for [highlight] renders plain static text: when the same copy toggles between "idle" and "in progress",
 * reuse one component and swap just this parameter (avoids writing two sets of Text parameters in two branches).
 */
@Composable
internal fun RippleText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    style: TextStyle = MaterialTheme.typography.labelMedium,
    highlight: Color? = Color.White
) {
    val rippleModifier = if (highlight != null) modifier.rippleHighlight(highlight) else modifier
    Text(
        text = text,
        color = color,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = rippleModifier
    )
}

/**
 * Unified "what's busy right now" status row: ripple-highlighted label (+ optional bouncing dots).
 *
 * The highlight only covers the label; the three bouncing dots are a separate animation and don't take the highlight (two layered animations would smear).
 * The outer [modifier] (fill width, fixed height) belongs to the container; the inner content lays out at its own width, left-aligned.
 *
 * Screen-reader semantics come from this row as a whole (the label is the semantics), so the inner [TypingDots] disables its own announcement.
 */
@Composable
internal fun AgentBusyIndicator(
    label: String,
    modifier: Modifier = Modifier,
    showDots: Boolean = false,
    dotColor: Color = MaterialTheme.colorScheme.primary,
    dotSize: Dp = 6.dp
) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            RippleText(text = label)
            if (showDots) TypingDots(color = dotColor, dotSize = dotSize, announce = false)
        }
    }
}

/**
 * Status row while waiting for the model: ripple-highlighted label + three bouncing dots, no outlined card.
 *
 * The caller decides whether [label] says "thinking" or something more specific (e.g. "editing a file" once
 * the model starts emitting a tool call's arguments); see [toolRunningLabelRes].
 */
@Composable
internal fun ThinkingBubble(label: String) {
    AgentBusyIndicator(
        label = label,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChatStyle.toolRowMinHeight),
        showDots = true
    )
}

/** Temporary status row during context compaction; not persisted. */
@Composable
internal fun CompactionProgressBubble() {
    AgentBusyIndicator(
        label = stringResource(R.string.chat_compressing_context),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChatStyle.toolRowMinHeight),
        showDots = true
    )
}

/** Temporary status row during network retries; not persisted. First line shows the specific error that triggered the retry (e.g. 429/500/network drop), second line shows retry progress. */
@Composable
internal fun RetryingBubble(attempt: Int, maxRetries: Int, error: RetryErrorInfo?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (error != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Icon(
                    FeatherIcons.AlertCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = retryErrorLabel(error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.chat_retrying, attempt, maxRetries),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            TypingDots(color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Temporary status row during multi-key auto-switching; not persisted. Shows the current key being unavailable and resending with key N of M.
 *
 * Like [RetryingBubble] and [CompactionProgressBubble], a transient status row in the tail that keeps the flat document flow:
 * no outlined card, just icon + label + bouncing dots.
 */
@Composable
internal fun KeySwitchedBubble(newIndex: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            FeatherIcons.Key,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = stringResource(R.string.chat_key_switched, newIndex, total),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        TypingDots(color = MaterialTheme.colorScheme.primary)
    }
}

/** Error summary text: category label + status code (e.g. "rate limited (429)"); category label only when there is no code. */
@Composable
private fun retryErrorLabel(error: RetryErrorInfo): String {
    val base = stringResource(
        when (error.kind) {
            RetryErrorKind.RATE_LIMIT -> R.string.retry_error_rate_limit
            RetryErrorKind.SERVER_OVERLOADED -> R.string.retry_error_server_overloaded
            RetryErrorKind.SERVER_ERROR -> R.string.retry_error_server
            RetryErrorKind.TIMEOUT -> R.string.retry_error_timeout
            RetryErrorKind.CONNECTION_REFUSED -> R.string.retry_error_connection_refused
            RetryErrorKind.DNS_FAILED -> R.string.retry_error_dns_failed
            RetryErrorKind.CONNECTION_RESET -> R.string.retry_error_connection_reset
            RetryErrorKind.SSL_ERROR -> R.string.retry_error_ssl
            RetryErrorKind.NETWORK -> R.string.retry_error_network
            RetryErrorKind.UNKNOWN -> R.string.retry_error_unknown
        }
    )
    val code = error.statusCode
    return if (code != null) stringResource(R.string.retry_error_with_code, base, code) else base
}

/** Streaming render throttle: after the text changes, wait this long before updating the rendered text, lowering the md parsing frequency. */
private const val STREAMING_RENDER_DEBOUNCE_MS = 120L

/**
 * Throttled rendering for continuously growing streaming text: the first frame renders the current text immediately, afterwards each change updates
 * at most once per [STREAMING_RENDER_DEBOUNCE_MS]. Every upstream delta carries the full accumulated text;
 * without throttling, every token would trigger a full md parse (slow parsing and laggy rendering on long texts).
 * The returned text is for rendering only; real-time logic like collapse checks still uses the raw [text].
 */
@Composable
private fun rememberThrottledStreamingText(text: String): String {
    var renderText by remember { mutableStateOf(text) }
    LaunchedEffect(text) {
        if (renderText == text) return@LaunchedEffect
        delay(STREAMING_RENDER_DEBOUNCE_MS)
        if (renderText != text) renderText = text
    }
    return renderText
}

/** Minimum typewriter display rate (chars/sec): keeps catching up at a steady pace while the upstream pauses, guaranteeing the ending is seen. */
private const val TYPEWRITER_MIN_RATE = 30f

/** Normal display rate ceiling (chars/sec): applies while lag stays within [TYPEWRITER_LAG_TARGET]. */
private const val TYPEWRITER_MAX_RATE = 220f

/** Emergency rate ceiling (chars/sec) once lag exceeds the target: allowed to break the normal cap and burn through the backlog fast. */
private const val TYPEWRITER_BURST_RATE = 1600f

/** Display rate = upstream rate × this ratio: slightly below 1, preserving that always-half-a-beat-behind feel. */
private const val TYPEWRITER_FOLLOW_RATIO = 0.95f

/**
 * Allowed lag (characters): the display may fall at most this many characters behind the upstream.
 *
 * Beyond it the [TYPEWRITER_MAX_RATE] cap no longer applies. This ceiling is the key to "no jerk at the finish" —
 * when the upstream ends, what's left to type is always this last short stretch, not a whole block of hundreds of piled-up characters.
 */
private const val TYPEWRITER_LAG_TARGET = 12f

/** Catch-up time constant (seconds): lag converges at this pace; smaller means tighter tracking. */
private const val TYPEWRITER_SETTLE_SECONDS = 0.06f

/**
 * Target time (seconds) to type out the remaining text after the upstream ends.
 *
 * The ending is no longer completed in a single frame (that's exactly where "suddenly jumping to the end" came from); instead the remainder is typed evenly over "remaining ÷ this duration":
 * no teleporting, yet not so slow the user thinks it stalled. A typical remainder (a dozen or so characters) takes about 0.2s.
 */
private const val TYPEWRITER_DRAIN_SECONDS = 0.2f

/** Display rate ceiling (chars/sec) for the drain phase: even a large remainder won't flash by in an instant. */
private const val TYPEWRITER_DRAIN_MAX_RATE = 400f

/** Hard cap for the drain phase (ms): even in extreme cases (text fully replaced, rendering falling behind) it must catch up within this. */
private const val TYPEWRITER_DRAIN_HARD_MS = 600L

/** Sliding window length (ms) for estimating the upstream character emission rate. */
private const val TYPEWRITER_RATE_WINDOW_MS = 500L

/**
 * Typewriter render throttle interval (ms).
 *
 * Each snapshot = a full Markdown re-parse + a full re-layout/measurement of the body Text (millisecond-scale heavy work on long replies).
 * 24ms (~42 per second) on a 120Hz screen means shouldering that heavy work every 3 frames, systematically blowing the frame budget while streaming.
 * A typewriter is a discrete "characters appear one by one" animation, not continuous motion, and the human eye perceives it as smooth at ~20fps,
 * so it is relaxed to 48ms (~21 per second): heavy-work frequency halved with no visible loss.
 */
private const val TYPEWRITER_RENDER_INTERVAL_MS = 48L

/** Truncates a string by code point count, avoiding cutting surrogate pairs like emoji into orphaned half-characters. */
private fun truncateToCodePoints(text: String, codePoints: Int): String {
    if (codePoints <= 0) return ""
    if (codePoints >= text.codePointCount(0, text.length)) return text
    var index = 0
    var count = 0
    while (index < text.length && count < codePoints) {
        index += Character.charCount(text.codePointAt(index))
        count++
    }
    return text.substring(0, index)
}

/** Prefix sampling cap (characters) for the continuation check: store only a fingerprint, not the full text, to keep large streaming texts out of saveable. */
private const val CONTINUITY_HEAD_CHARS = 64

/** Prefix fingerprint of already-seen text; together with its length it forms the "same-turn continuation" check. */
internal fun streamHeadFingerprint(text: String): Int =
    text.take(CONTINUITY_HEAD_CHARS).hashCode()

/**
 * Whether [text] continues the already-seen text of length [seenChars] with prefix fingerprint [seenHead].
 *
 * Streaming text grows prefix-wise delta by delta, so within one turn the current text always has the seen text as a prefix. After a page switch or item
 * recycling remount, this validates whether the restored typing progress / timer start still belongs to the same turn: if the turn changed meanwhile, the new text is shorter
 * or starts differently, fails the continuation check, and progress and timing restart from scratch.
 */
internal fun isStreamContinuation(text: String, seenChars: Int, seenHead: Int): Boolean {
    if (seenChars <= 0 || text.length < seenChars) return false
    return text.take(minOf(seenChars, CONTINUITY_HEAD_CHARS)).hashCode() == seenHead
}

/**
 * Typewriter display rate per frame (code points/sec). [lag] = characters arrived upstream − characters shown, [arrivalRate] is the sliding-window estimate of the
 * upstream emission rate, and a non-null [drainRate] marks the drain phase after the upstream ended (typing out the remainder evenly at that constant rate).
 *
 * During playback, take the larger of the two:
 * - follow term = upstream rate × [TYPEWRITER_FOLLOW_RATIO] (slightly slower than upstream, preserving the half-a-beat-behind look);
 * - catch-up term = lag ÷ [TYPEWRITER_SETTLE_SECONDS] (the larger the lag, the faster the catch-up).
 *
 * The normal cap [TYPEWRITER_MAX_RATE] applies only while lag is within bounds: once lag exceeds [TYPEWRITER_LAG_TARGET],
 * it relaxes to [TYPEWRITER_BURST_RATE]. This rule is the key to "no jerk at the finish" — the old implementation pinned the rate at
 * 200 chars/sec, so when the model emitted fast or in bursts the lag only grew (steady state ~1.8× the upstream rate, often hundreds of characters),
 * and the instant the upstream ended the whole block jumped out at once, looking like a sudden jerk. Now the lag is held within a dozen or so characters,
 * and the drain phase types out that last short stretch evenly over [TYPEWRITER_DRAIN_SECONDS] — no teleporting.
 */
internal fun typewriterRate(lag: Float, arrivalRate: Float, drainRate: Float? = null): Float {
    if (lag <= 0f) return 0f
    if (drainRate != null) {
        return drainRate.coerceIn(TYPEWRITER_MIN_RATE, TYPEWRITER_BURST_RATE)
    }
    val followRate = arrivalRate * TYPEWRITER_FOLLOW_RATIO
    val catchUpRate = lag / TYPEWRITER_SETTLE_SECONDS
    val ceiling = if (lag > TYPEWRITER_LAG_TARGET) TYPEWRITER_BURST_RATE else TYPEWRITER_MAX_RATE
    return maxOf(followRate, catchUpRate).coerceIn(TYPEWRITER_MIN_RATE, ceiling)
}

/**
 * Drain rate (code points/sec): types out the [lag] remaining code points evenly within [TYPEWRITER_DRAIN_SECONDS].
 *
 * Spreading the remainder over the target duration (rather than catching up at a fixed multiple) keeps the drain time constant (~0.2s):
 * a dozen remaining characters still visibly read as typing, and hundreds of remaining ones won't crawl cell by cell until the user thinks it stalled.
 */
internal fun typewriterDrainRate(lag: Float): Float =
    (lag / TYPEWRITER_DRAIN_SECONDS).coerceIn(TYPEWRITER_MIN_RATE, TYPEWRITER_DRAIN_MAX_RATE)

/** Current typewriter render result: [text] is the text to render (a prefix of the upstream full text), [settled] tells whether it has caught up with the full text. */
internal data class TypewriterText(val text: String, val settled: Boolean)

/**
 * Rate-adaptive typewriter: the displayed text lags the upstream accumulated text, and the typing speed follows the model's emission speed.
 *
 * Every upstream delta carries the full accumulated text, so its arrival rhythm is the model's emission rhythm. Two progresses are tracked here:
 * arrival progress (code points of [text]) and display progress (code points shown). Display progress is frame-driven at the rate from
 * [typewriterRate]: the lag is held near [TYPEWRITER_LAG_TARGET] and never piles up.
 *
 * The rendered text is snapshotted every [TYPEWRITER_RENDER_INTERVAL_MS] (throttle, not debounce,
 * so rendering keeps visibly growing while typing), pressing the md parsing frequency down to ~10fps; when text jumps (session switch /
 * new turn / retry) it completes to the current full text, then keeps typing along with the deltas.
 *
 * When the upstream ends ([active] turns false) it is **no longer completed in one frame**: the remaining short stretch is typed out evenly at
 * [typewriterDrainRate] (~0.2s, with the hard cap [TYPEWRITER_DRAIN_HARD_MS] as fallback); meanwhile [TypewriterText.settled]
 * stays false, and the caller uses that to hand this text's rendering over to the just-persisted assistant message (see AIChatPanel);
 * once done, the persisted message fully takes over — the user never sees "the whole block pop out at once", nor the same text duplicated.
 *
 * The caller should hold this state outside the LazyColumn, so the tail item scrolling out of the viewport and being disposed
 * doesn't lose typing progress on recomposition. Page switches (the whole chat subtree leaving the NavHost composition) can't be dodged by where it's held;
 * the internal saveable progress covers that.
 */
@Composable
internal fun rememberTypewriterStreamingText(
    text: String,
    active: Boolean,
    /**
     * The session the text belongs to. When switching to another session that is mid-output, what it has already produced is a fait accompli and must be shown completed at once —
     * this function's state hangs on the call site and a session switch doesn't rebuild it, so without distinguishing explicitly, the "turn change" check
     * below would treat it as a new turn and re-type that content character by character in front of the user.
     */
    sessionKey: String? = null
): TypewriterText {
    // The rendered text's length and prefix fingerprint go into saveable: on returning from a page switch, typing resumes from them instead of
    // re-typing the already-output body from scratch. When validation fails (the turn or session changed meanwhile), complete to the current full text rather than restarting:
    // text seen for the first time at the moment of mounting is history to the user, and re-typing it just looks like the model repeating itself.
    var shownChars by rememberSaveable { mutableStateOf(0) }
    var shownHead by rememberSaveable { mutableStateOf(0) }
    val restored = remember {
        if (isStreamContinuation(text, shownChars, shownHead)) text.substring(0, shownChars) else text
    }
    var shownCodePoints by remember {
        mutableStateOf(restored.codePointCount(0, restored.length).toFloat())
    }
    var renderText by remember { mutableStateOf(restored) }
    // Upstream arrival event window: (frame timestamp, cumulative code points), used to estimate the emission rate
    val arrivals = remember { ArrayDeque<Pair<Long, Int>>() }
    var lastArrivalNanos by remember { mutableStateOf(0L) }
    var lastText by remember { mutableStateOf(restored) }
    var lastSessionKey by remember { mutableStateOf(sessionKey) }
    // The rendered text and its saveable fingerprint must update in lockstep, or restoration would validate the fingerprint against a different text
    val commitRender: (String) -> Unit = { snapshot ->
        renderText = snapshot
        shownChars = snapshot.length
        shownHead = streamHeadFingerprint(snapshot)
    }

    LaunchedEffect(text, active, sessionKey) {
        // The text doesn't continue the current progress (session switch / new turn / retry): complete to the current full text, then keep typing with subsequent deltas.
        // It must not restart from zero — switching to another mid-output session would replay its hundreds of already-produced characters in front of the user.
        // The session switch must be detected separately: currentSessionId and streamingText don't necessarily arrive in the same frame, and prefix-only detection
        // would miss whichever frame lands first (that frame's text still belongs to the old session, showing no jump).
        // A new turn also takes this path, but at that point text holds only the first delta's few characters, so completing vs re-typing looks identical.
        val sessionChanged = sessionKey != lastSessionKey
        lastSessionKey = sessionKey
        if (sessionChanged || (lastText.isNotEmpty() && !text.startsWith(lastText))) {
            shownCodePoints = text.codePointCount(0, text.length).toFloat()
            commitRender(text)
            arrivals.clear()
            lastArrivalNanos = 0L
        }
        lastText = text

        // The upstream has ended; enter the drain phase: no more one-frame completion (that's exactly what caused the jerk at the finish),
        // the remaining characters are typed out evenly at a constant rate. text won't change again here, and this coroutine runs uninterrupted during the drain.
        val draining = !active
        val now = System.nanoTime()
        val codePoints = text.codePointCount(0, text.length)
        // The drain rate is set from the remainder at drain-entry, keeping the drain duration constant and letting it finish exactly (no fractional leftover)
        val drainRate = if (draining) {
            typewriterDrainRate((codePoints - shownCodePoints).coerceAtLeast(0f))
        } else {
            null
        }
        val drainDeadlineNanos = now + TYPEWRITER_DRAIN_HARD_MS * 1_000_000L
        if (!draining) {
            // Record this arrival event and trim the rate window (keep at least 2 entries within the recent WINDOW)
            if (lastArrivalNanos != 0L) {
                arrivals.addLast(now to codePoints)
                val windowNanos = TYPEWRITER_RATE_WINDOW_MS * 1_000_000L
                while (arrivals.size > 2 && now - arrivals.first().first > windowNanos) {
                    arrivals.removeFirst()
                }
            }
            lastArrivalNanos = now
        }

        // Frame-driven advance of display progress; exit once this text is caught up (if text changes again this coroutine is cancelled and restarted)
        var lastRenderNanos = 0L
        var lastFrameNanos = 0L
        while (shownCodePoints < codePoints) {
            withFrameNanos { frameNanos ->
                if (lastFrameNanos != 0L) {
                    val dtSec = (frameNanos - lastFrameNanos) / 1_000_000_000f
                    // The upstream rate only needs estimating during playback: in the drain phase the upstream is already idle
                    var arrivalRate = 0f
                    if (!draining) {
                        val oldest = arrivals.firstOrNull()
                        if (arrivals.size >= 2 && oldest != null) {
                            val spanSec = (frameNanos - oldest.first) / 1_000_000_000f
                            if (spanSec > 0f) {
                                arrivalRate = (arrivals.last().second - oldest.second) / spanSec
                            }
                        }
                    }
                    val rate = typewriterRate(
                        lag = codePoints - shownCodePoints,
                        arrivalRate = arrivalRate,
                        drainRate = drainRate
                    )
                    shownCodePoints = (shownCodePoints + rate * dtSec)
                        .coerceAtMost(codePoints.toFloat())
                }
                lastFrameNanos = frameNanos

                // Hard cap on the drain: even in extreme cases (text fully replaced, rendering falling behind) it must catch up within this,
                // otherwise the tail stays stuck uncaught-up and the persisted message never gets the handoff.
                if (draining && frameNanos >= drainDeadlineNanos) {
                    shownCodePoints = codePoints.toFloat()
                }

                // Render throttling: snapshot the current display progress at each interval; force-render the full text the instant it catches up
                val intervalNanos = TYPEWRITER_RENDER_INTERVAL_MS * 1_000_000L
                if (frameNanos - lastRenderNanos >= intervalNanos || shownCodePoints >= codePoints) {
                    val snapshot = truncateToCodePoints(text, shownCodePoints.toInt())
                    if (snapshot != renderText) {
                        lastRenderNanos = frameNanos
                        commitRender(snapshot)
                    }
                }
            }
        }
        // After catching up, make sure the full text is rendered (shownCodePoints has reached available when the while exits)
        if (renderText != text) commitRender(text)
    }
    // settled comes straight from "rendered text equals the upstream full text": during the drain the caller uses it to decide who owns rendering
    return TypewriterText(text = renderText, settled = renderText == text)
}

/**
 * Live bubble while the model streams characters: left-aligned, same style as the assistant bubble.
 * Three bouncing dots at the tail mark ongoing generation. Once this turn ends, the persisted assistant bubble takes over.
 *
 * During streaming it renders with the typewriter effect (typing progress driven by the caller via
 * [rememberTypewriterStreamingText], see [AIChatPanel]); the typing speed adapts to the model's
 * emission speed, and when the upstream ends it auto-completes to the full text, handing off seamlessly to the persisted message.
 */
@Composable
internal fun StreamingBubble(
    text: String,
    cache: MarkdownRenderCache? = null,
    /** Streaming in progress (typewriter not caught up): MarkdownContent parsing is incrementally throttled and intermediate snapshots skip the cache. */
    streaming: Boolean = false
) {
    val renderText = text
    // Isomorphic to the persisted assistant body: no container, laid directly on the page background, with "generating" dots hanging at the bottom
    Column(modifier = Modifier.fillMaxWidth()) {
        MarkdownContent(
            text = renderText,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
            cache = cache,
            streaming = streaming
        )
        Spacer(Modifier.height(Spacing.xs))
        TypingDots(color = MaterialTheme.colorScheme.primary, dotSize = 5.dp)
    }
}

/**
 * The single line of reasoning shown in the collapsed row: while thinking, takes the **last line** (scrolling quickly with what the model is writing);
 * once thinking ends, takes the **first line** (content is settled, frozen as a one-line preview).
 *
 * The caller renders single-line + ellipsis, so leading Markdown markers (headings, lists, quotes) and trailing closing
 * markers (bold, inline code) are stripped here, keeping `##` / `**` style source markers out of the collapsed row; if the line is still too long,
 * the ellipsis truncates it. Returns an empty string when there is no visible content, and the caller falls back to the "reasoning" label.
 */
internal fun reasoningPreviewLine(raw: String, live: Boolean): String {
    val line = if (live) {
        // Walk back from the end skipping whitespace and newlines to cut out the last line. No whole-string trimEnd(): under streaming reasoning this runs every frame,
        // and copying the whole long reasoning text would be pure waste.
        var end = raw.length
        while (end > 0 && raw[end - 1].isWhitespace()) end--
        var start = end
        while (start > 0 && raw[start - 1] != '\n') start--
        raw.substring(start, end)
    } else {
        raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    }
    // Spaces and Markdown markers alternate at both ends (`- **point**`, `## heading`); collect them in one set and strip in a single pass
    return line.trim(*REASONING_PREVIEW_MARKERS)
}

/** Characters stripped from both ends of the collapsed-row preview: whitespace + Markdown markers (heading `#`, list `-` `+` `*`, quote `>`, bold and inline code `*` `` ` ``). */
private val REASONING_PREVIEW_MARKERS = charArrayOf(' ', '\t', '#', '*', '-', '>', '`', '+')

/** Max height of the expanded reasoning window: beyond it, scroll inside the window instead of growing unbounded and hijacking the whole page's scroll. */
private val ReasoningWindowMaxHeight = 300.dp

/** Height of the bottom fade mask in the reasoning window (shown only while more content can scroll down). */
private val ReasoningWindowFadeHeight = 28.dp

/**
 * Collapsed reasoning row: left-aligned, muted, distinct from the formal reply. **Collapsed by default**; tap the row to expand/collapse anytime.
 *
 * Collapsed it takes a single line: leading thinking icon + a one-line preview (see [reasoningPreviewLine], ellipsized when too wide);
 * expanded it becomes the full body (Markdown rendered). There is no middle state showing "the last few lines".
 *
 * [live] means reasoning is still in progress: the collapsed preview takes the last line and scrolls with the content, so the model's thinking is visible without expanding;
 * once reasoning ends, the preview freezes to the first line.
 */
@Composable
internal fun ReasoningBubble(
    text: String,
    cache: MarkdownRenderCache? = null,
    showTimer: Boolean = false,
    /** Total duration of finished reasoning (milliseconds). When non-null and greater than 0, the collapsed row label shows the exact duration. */
    durationMs: Long? = null,
    timerStartMs: Long? = null,
    /** The text is already driven by an external typewriter (streaming-tail scenario); skip the internal debounce and render directly. */
    preRendered: Boolean = false,
    /** The session this reasoning belongs to: retime on session switch, otherwise the previous session's start point yields absurd durations. */
    sessionKey: String? = null,
    /** Reasoning still in progress: the collapsed-row preview takes the last line (scrolling with it); see the KDoc above. */
    live: Boolean = false,
    /** External override for the expanded state (persisted by the host, see [AIAgentViewModel.reasoningExpansionOverrides]); null = use internal state. */
    expandedOverride: Boolean? = null,
    /** Reports the new state when manually expanded/collapsed; persisted by the layer above. */
    onExpandedChange: ((Boolean) -> Unit)? = null
) {
    var localExpanded by remember { mutableStateOf(false) }
    val expanded = expandedOverride ?: localExpanded
    val toggleExpanded: (Boolean) -> Unit = { next ->
        if (onExpandedChange != null) onExpandedChange(next) else localExpanded = next
    }
    // Store an absolute start timestamp rather than accumulated
    // milliseconds, so the shown duration stays real after a page-switch return or a remount when the bubble scrolls out of the viewport; the start stamp goes into saveable together with the seen text's length and
    // fingerprint, and if the restored text isn't a continuation of the same turn (the turn changed meanwhile), timing restarts.
    var timerStartMillis by rememberSaveable { mutableStateOf(0L) }
    var timerSeenChars by rememberSaveable { mutableStateOf(0) }
    var timerSeenHead by rememberSaveable { mutableStateOf(0) }
    var elapsedMillis by remember { mutableStateOf(0L) }
    val latestText by rememberUpdatedState(text)
    LaunchedEffect(showTimer, live, sessionKey, timerStartMs) {
        if (!showTimer || !live) return@LaunchedEffect
        if (timerStartMs != null) {
            timerStartMillis = timerStartMs
        } else if (!isStreamContinuation(latestText, timerSeenChars, timerSeenHead)) {
            timerStartMillis = System.currentTimeMillis()
        }
        while (true) {
            elapsedMillis = (System.currentTimeMillis() - timerStartMillis).coerceAtLeast(0L)
            timerSeenChars = latestText.length
            timerSeenHead = streamHeadFingerprint(latestText)
            delay(100)
        }
    }
    // Expanded rendering uses the throttled text (lowering md parse frequency during streaming reasoning); with preRendered, the outside already supplies render text at the typewriter's pace.
    val renderText = if (preRendered) text else rememberThrottledStreamingText(text)
    val effectiveDurationMs = durationMs?.takeIf { it > 0 } ?: elapsedMillis.takeIf { it > 0 }
    val formattedDuration = remember(effectiveDurationMs, live) {
        effectiveDurationMs?.let { ms ->
            val sec = (ms / 1000.0).coerceAtLeast(0.1)
            String.format(java.util.Locale.US, "%.1f", sec)
        }
    }
    // Collapsed-row label: live duration while in progress, total duration once done (just "thinking finished" when no duration data is available).
    val label = when {
        live -> {
            val secText = formattedDuration ?: "0.1"
            stringResource(R.string.chat_thinking_running_time, secText)
        }
        formattedDuration != null -> stringResource(R.string.chat_thinking_done_time, formattedDuration)
        else -> stringResource(R.string.chat_thinking_done)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp)
                .clickable { toggleExpanded(!expanded) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThinkingGlyph(tint = Brand.IconGray, iconSize = 16.dp)
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(Spacing.xs))
            ExpandableChevronIcon(
                expanded = expanded,
                contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                tint = Brand.IconGray,
                size = 18.dp
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(Spacing.sm))
                val scrollState = rememberScrollState()
                val fadeColor = MaterialTheme.colorScheme.background
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MarkdownContent(
                        text = renderText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        cache = cache,
                        compact = true,
                        // During streaming reasoning (external typewriter advancing text): throttled parsing + intermediate snapshots skip the cache
                        streaming = live && preRendered,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = ReasoningWindowMaxHeight)
                            .nestedScroll(rememberBoundNestedScrollConnection(scrollState))
                            .verticalScroll(scrollState)
                            .pointerInput(text) {
                                detectTapGestures(
                                    onDoubleTap = { toggleExpanded(false) }
                                )
                            }
                    )
                    // Bottom fade: only cover a layer while more content can scroll down, hinting "more content below"
                    if (scrollState.canScrollForward) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(ReasoningWindowFadeHeight)
                                .background(Brush.verticalGradient(listOf(Color.Transparent, fadeColor)))
                        )
                    }
                }
            }
        }
    }
}

/**
 * Three cyclically bouncing dots: a generic "typing/generating" indicator replacing the spinning spinner.
 * The three dots bounce up and down in sequence with a fixed phase offset, forming a wave-like rhythm.
 *
 * Performance: graphicsLayer { translationY } replaces offset(y), so animation value changes are handled in the draw phase
 * without triggering compose/recompose, eliminating the per-frame recomposition cost an infinite animation would impose on the parent layout.
 * The container height is fixed, preventing layout jitter from propagating into the LazyColumn.
 */
@Composable
internal fun TypingDots(
    color: Color,
    dotSize: androidx.compose.ui.unit.Dp = 6.dp,
    /** Whether to announce "generating" to screen readers on its own: when nested in [AgentBusyIndicator], the outer status row announces it uniformly. */
    announce: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "typing-dots")
    val label = stringResource(R.string.chat_status_generating)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // Give screen readers a semantic: three bouncing dots are otherwise completely invisible to TalkBack.
        modifier = Modifier
            .height(dotSize + 10.dp)
            .then(
                if (announce) Modifier.semantics { contentDescription = label } else Modifier
            )
    ) {
        repeat(3) { index ->
            val offsetY by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 900
                        0f at 0
                        -5f at 180
                        0f at 360
                        0f at 900
                    },
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(index * 150)
                ),
                label = "dot-$index"
            )
            Box(
                modifier = Modifier
                    .graphicsLayer { translationY = offsetY }
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color)
            )
            if (index < 2) Spacer(Modifier.width(4.dp))
        }
    }
}
