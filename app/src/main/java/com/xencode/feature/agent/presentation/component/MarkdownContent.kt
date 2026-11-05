package com.xencode.feature.agent.presentation.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xencode.R
import com.xencode.core.theme.semanticColors
import com.mikepenz.markdown.compose.LazyMarkdownSuccess
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.MarkdownSuccess
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.State as MarkdownParseState
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Code blocks and inline code share this font. The system monospace (FontFamily.Monospace) lacks subscript glyphs and does not fall back,
// while the bundled JetBrains Mono NL loaded from resources can go through system fallback and display subscript/superscript characters like ₀-₉.
private val CodeFontFamily = FontFamily(Font(R.font.jetbrains_mono_nl))

/** Base increment (in characters) for streaming parse throttling: scaled up proportionally with text length (+1 per 256 chars).
 *  Typewriter snapshots advance about every 24ms; at low output speed a batch differs by only a few characters (still looks character-by-character); when catching up at high speed a batch is at most
 *  8+len/256 characters (imperceptible at the end of long text), cutting how often the full preprocess+parse runs to 1/3~1/10 of before. */
private const val STREAMING_PARSE_MIN_BASE = 8

internal class MarkdownRenderCache(
    // 200 entries: when scrolling back and forth over hundreds of messages in a long session, an 80-entry capacity causes LRU thrashing (a message just scrolled past
    // misses again on re-entering the viewport and gets fully re-parsed). A single AST+state is a few KB to tens of KB, so 200 entries is an acceptable memory footprint.
    private val maxEntries: Int = 200
) {
    private val parsedStates = object : LinkedHashMap<String, MarkdownParseState.Success>(
        maxEntries,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MarkdownParseState.Success>?): Boolean {
            return size > maxEntries
        }
    }

    fun get(text: String): MarkdownParseState.Success? =
        parsedStates[text] ?: parsedStates[text.trim()]

    /**
     * Finds the longest cached parse state that is a prefix of [text].
     * At the streaming-to-persisted handoff instant, the full text may have tail characters beyond the last typewriter frame; fall back to the longest prefix AST,
     * ensuring the first frame shows fully typeset Markdown and completely eliminating raw plain text (- **xxx**) flashes.
     */
    fun getBestPrefix(text: String): MarkdownParseState.Success? {
        val exact = get(text)
        if (exact != null) return exact
        var bestMatch: MarkdownParseState.Success? = null
        var bestLength = 0
        for ((key, value) in parsedStates) {
            if (key.length in (bestLength + 1)..text.length && text.startsWith(key)) {
                bestMatch = value
                bestLength = key.length
            }
        }
        return bestMatch
    }

    fun put(state: MarkdownParseState.Success) {
        parsedStates[state.content] = state
        val trimmed = state.content.trim()
        if (trimmed != state.content) {
            parsedStates[trimmed] = state
        }
    }
}

internal fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000_000L -> "%.1fB".format(tokens / 1_000_000_000.0)
    tokens >= 1_000_000L -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000L -> "%.1fk".format(tokens / 1_000.0)
    else -> tokens.toString()
}

@Composable
internal fun MarkdownContent(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    cache: MarkdownRenderCache? = null,
    compact: Boolean = false,
    /** Content shown while parsing is in progress (Loading); when null, falls back to showing the raw plain text (chat streaming scenario). */
    loading: (@Composable () -> Unit)? = null,
    /** Virtualized rendering for long text: the body renders block by block via LazyColumn (the caller must supply a bounded height constraint such as heightIn
     *   to trigger lazy loading), avoiding the jank of composing/measuring a huge md all at once. */
    lazyScroll: Boolean = false,
    /** The caller is mid-streaming (typewriter text advances every ~24ms): parse input is throttled by an increment threshold — batch up
     *  [STREAMING_PARSE_MIN_BASE] + length/256 characters before re-running preprocess+parse; other frames keep rendering
     *  the last successful result (content only grows, never jumps, at most one batch of characters behind). Meanwhile streaming intermediate snapshots are not written to [cache]:
     *  one every 24ms would flood the LRU and evict every historical message's parse result; scrolling back to history would re-parse each one in full,
     *  which is exactly the source of composition spikes (15ms+ per frame) and dropped frames while scrolling chat history. */
    streaming: Boolean = false
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val semantic = MaterialTheme.semanticColors

    // markdownColor / markdownTypography / markdownPadding / markdownDimens / markdownComponents
    // are all @Composable factory functions provided by the library (with their own internal remember memoization); wrapping them in another layer of
    // remember is impossible and unnecessary (doing so errors with "@Composable invocations can only happen from ..."). Just call them directly.
    val mdColors = markdownColor(
        text = color,
        codeBackground = semantic.mutedSurface,
        inlineCodeBackground = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.12f),
        dividerColor = semantic.subtleBorder,
        tableBackground = semantic.mutedSurface,
    )

    val typography = MaterialTheme.typography
    // compact: body and lists use bodySmall, headings drop one tier; used for secondary text areas like the reasoning bubble
    val body = if (compact) typography.bodySmall else typography.bodyMedium
    val bodyLineHeight = if (compact) 18.sp else 20.sp
    val codeSize = if (compact) 12.sp else 13.sp
    val mdTypography = markdownTypography(
        h1 = (if (compact) typography.titleMedium else typography.headlineSmall).copy(fontWeight = FontWeight.Bold, color = color),
        h2 = (if (compact) typography.titleSmall else typography.titleLarge).copy(fontWeight = FontWeight.Bold, color = color),
        h3 = (if (compact) typography.bodyLarge else typography.titleMedium).copy(fontWeight = FontWeight.SemiBold, color = color),
        h4 = (if (compact) typography.bodyMedium else typography.titleSmall).copy(fontWeight = FontWeight.SemiBold, color = color),
        h5 = (if (compact) typography.bodySmall else typography.bodyLarge).copy(fontWeight = FontWeight.Medium, color = color),
        h6 = (if (compact) typography.bodySmall else typography.bodyMedium).copy(fontWeight = FontWeight.Medium, color = color),
        paragraph = body.copy(color = color, lineHeight = bodyLineHeight),
        code = TextStyle(fontFamily = CodeFontFamily, fontSize = codeSize, color = MaterialTheme.colorScheme.onSurface),
        inlineCode = TextStyle(fontFamily = CodeFontFamily, color = MaterialTheme.colorScheme.onSurface),
        ordered = body.copy(color = color, lineHeight = bodyLineHeight),
        bullet = body.copy(color = color, lineHeight = bodyLineHeight),
        table = typography.bodySmall.copy(color = color),
    )

    val mdPadding = markdownPadding(
        block = 4.dp,
        list = 2.dp,
        listItemBottom = 1.dp,
        listIndent = 12.dp,
        codeBlock = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    )

    val mdDimens = markdownDimens(
        codeBackgroundCornerSize = 6.dp,
        tableCellPadding = 6.dp,
        tableCornerSize = 6.dp,
    )

    val highlightsBuilder = remember(isDark) {
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = isDark))
    }

    // Streaming throttle: new typewriter text arriving every ~24ms is batched first — while the increment is below the threshold, keep the text last submitted for parsing,
    // and neither preprocess nor Markdown parsing re-runs; text shrinking/replacement (not growth) passes through immediately to prevent flicker.
    // Once settled (streaming becomes false) this branch no longer hits, so the final full text is always fully parsed once.
    var lastParsedText by remember { mutableStateOf<String?>(null) }
    val parseText: String = if (!streaming) {
        text
    } else {
        val last = lastParsedText
        val delta = STREAMING_PARSE_MIN_BASE + text.length / 256
        when {
            last == null || !text.startsWith(last) || text.length - last.length >= delta ->
                text.also { lastParsedText = it }
            else -> last
        }
    }
    val processed = remember(parseText) { MarkdownPreprocessor.process(parseText) }
    val baseTransformer = LocalMarkdownImageTransformer.current
    val mathTransformer = remember(baseTransformer, body.fontSize.value) {
        MathImageTransformer(baseTransformer, body.fontSize.value)
    }

    // Prewarm every LaTeX formula in this message on a background thread: compute measure+render ahead of time when the item first composes
    // and write the cache, so during a fast fling the main-thread getOrMeasure hits directly instead of synchronously building the drawable (measured at up to 113ms
    // per call — the direct root cause of fling stutter).
    val density = LocalDensity.current
    val textSizePx = with(density) { body.fontSize.toPx() }
    val colorArgb = color.toArgb()
    LaunchedEffect(processed, textSizePx, colorArgb) {
        if (!processed.contains("xencode-math-")) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            MathImageTransformer.prewarm(processed, textSizePx, colorArgb)
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides color
    ) {
        // rememberMarkdownState must participate in composition unconditionally: if a cache hit skipped it via an if branch, its internal
        // remember state would be disposed; on the next text change (streaming growth/resume after a pause) the cache-miss rebuild starts
        // in Loading and flashes raw md text (the root cause of parsed/unparsed flip-flopping).
        // The cache is only a rendering accelerator: use the parse result directly when it matches the current text; when it does not match but the cache
        // hits the current text, use the cache; otherwise enter Loading. Note: this library's rememberMarkdownState unconditionally sets
        // state to Loading on text change (the retainState parameter has no effect in this version); if the Loading branch fell back to
        // showing the raw plain text, every text change during streaming would flash the latest text as bare text (characters flickering at the bottom).
        // So during Loading, prefer rendering the most recent successful parse (the old text's fully typeset md); once parsing
        // completes, switch smoothly to the new text — content only grows, never jumps.
        val mdState = rememberMarkdownState(content = processed, retainState = true)
        val parseState by mdState.state.collectAsState()
        val cachedState = cache?.get(processed)
        val prefixFallback = cache?.getBestPrefix(processed)
        // Delegated properties cannot be smart-cast; take a local snapshot first, then check
        val currentState = parseState
        val parsedState: MarkdownParseState = when {
            currentState is MarkdownParseState.Success && currentState.content == processed -> currentState
            cachedState != null -> cachedState
            else -> currentState
        }

        // Most recent successful parse: the rendering fallback during Loading (the very first frame already uses the prefix cache, no bare plain text)
        var lastSuccessState by remember { mutableStateOf<MarkdownParseState.Success?>(prefixFallback) }
        if (parsedState is MarkdownParseState.Success) {
            lastSuccessState = parsedState
            // Write the cache synchronously: the persisted message taking over in the same turn hits on its first frame, avoiding bare-plain-text flicker caused by LaunchedEffect's async delay.
            // Streaming intermediate snapshots are not written: one every ~24ms would flood the LRU and evict historical messages' parse results (scrolling back re-parses each one);
            // only the final full text after settle (streaming=false) lands in the cache, serving persisted messages and later scrolls.
            if (!streaming) cache?.put(parsedState)
        }

        // Success renders the current result; Loading prefers this component's or the prefix cache's successful result (no bare plain-text flash); Error falls back to the raw text
        val renderState: MarkdownParseState.Success? = when (parsedState) {
            is MarkdownParseState.Success -> parsedState
            is MarkdownParseState.Loading -> lastSuccessState ?: prefixFallback
            is MarkdownParseState.Error -> null
        }

        if (renderState != null) {
            // When long text renders block by block via LazyColumn, animations (text size animation etc.) do not apply — expected.
            // The three objects below (successRenderer / mdComponents / mdAnimations) are all remembered: uncached,
            // every recomposition would create new lambda instances and stop the downstream Markdown from skipping.
            val successRenderer: @Composable (
                state: MarkdownParseState.Success,
                components: MarkdownComponents,
                modifier: Modifier
            ) -> Unit = remember(lazyScroll) {
                if (lazyScroll) {
                    { state, components, m ->
                        LazyMarkdownSuccess(state = state, components = components, modifier = m)
                    }
                } else {
                    { state, components, m ->
                        MarkdownSuccess(state = state, components = components, modifier = m)
                    }
                }
            }

            // markdownComponents is @Composable (with its own internal memoization); it cannot be wrapped in remember — call it directly.
            val mdComponents = markdownComponents(
                // The outer SelectionContainer (MessageBubbles) owns selection uniformly; overlong assistant messages are already
                // split by AIChatPanel into multiple bounded items, so there is no selection-tree/interaction breakage from a single overlong item.
                codeFence = {
                    MarkdownCodeFence(
                        content = it.content,
                        node = it.node,
                    ) { code, language, style ->
                        SafeMarkdownHighlightedCode(code, language, style, highlightsBuilder, showHeader = true)
                    }
                },
                codeBlock = {
                    MarkdownCodeBlock(
                        content = it.content,
                        node = it.node,
                    ) { code, language, style ->
                        SafeMarkdownHighlightedCode(code, language, style, highlightsBuilder, showHeader = true)
                    }
                },
                // The library defaults to maxLines=1 + Ellipsis, truncating long cell text; here it is relaxed to full multi-line display.
                table = {
                    MarkdownTable(
                        content = it.content,
                        node = it.node,
                        style = it.typography.table,
                        headerBlock = { content, header, tableWidth, style ->
                            MarkdownTableHeader(
                                content = content,
                                header = header,
                                tableWidth = tableWidth,
                                style = style,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
                            )
                        },
                        rowBlock = { content, header, tableWidth, style ->
                            MarkdownTableRow(
                                content = content,
                                header = header,
                                tableWidth = tableWidth,
                                style = style,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
                            )
                        },
                    )
                },
            )

            Markdown(
                state = renderState,
                modifier = modifier,
                colors = mdColors,
                typography = mdTypography,
                padding = mdPadding,
                dimens = mdDimens,
                // Disable animateContentSize on paragraph text: under rapid streaming updates it keeps chasing the target height, causing bouncy jitter instead.
                animations = markdownAnimations(animateTextSize = { this }),
                imageTransformer = mathTransformer,
                components = mdComponents,
                success = successRenderer,
            )
        } else if (loading != null) {
            loading()
        } else {
            PlainMarkdownText(
                text = text,
                color = color,
                modifier = modifier
            )
        }
    }
}

/**
 * Code block highlight rendering: equivalent to the library's `MarkdownHighlightedCode`, but validates ranges before
 * writing highlight spans into the AnnotatedString. The highlights engine emits start > end ranges for some content; the library's original implementation calls
 * addStyle directly, and the AnnotatedString.Range constructor immediately throws "Reversed range is not supported"; the computation runs in
 * the library's own background coroutine (produceState + Dispatchers.Default), so the exception goes straight to the thread's default handler —
 * the caller's try/catch cannot intercept it, manifesting as a chat-page crash. Out-of-bounds (end > code.length) likewise crashes,
 * so those are dropped as well.
 */
/**
 * Process-wide cache of code highlight results. Highlighting itself runs on Dispatchers.Default (never blocks the main thread), but without a
 * cross-viewport cache, every item re-entry re-runs highlighting and then triggers another main-thread recomposition (switching from plain text to
 * highlighted text). Flinging fast over many code blocks floods the Default thread and creates dense recompositions. On a cache hit
 * the result is available synchronously as the produceState initial value — no recompute, no plain-text flash. The key includes isDark (theme affects colors).
 */
private object CodeHighlightCache {
    private data class Key(val code: String, val language: String?, val dark: Boolean)

    private const val MAX = 256
    private val cache = object : LinkedHashMap<Key, AnnotatedString>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, AnnotatedString>?): Boolean = size > MAX
    }

    fun get(code: String, language: String?, dark: Boolean): AnnotatedString? =
        synchronized(cache) { cache[Key(code, language, dark)] }

    fun put(code: String, language: String?, dark: Boolean, value: AnnotatedString) {
        synchronized(cache) { cache[Key(code, language, dark)] = value }
    }
}

@Composable
private fun SafeMarkdownHighlightedCode(
    code: String,
    language: String?,
    style: TextStyle,
    highlightsBuilder: Highlights.Builder,
    showHeader: Boolean,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cached = CodeHighlightCache.get(code, language, isDark)
    val highlighted: AnnotatedString by produceState(
        initialValue = cached ?: AnnotatedString(code),
        code,
        language,
        isDark,
    ) {
        if (CodeHighlightCache.get(code, language, isDark) != null) return@produceState
        value = withContext(Dispatchers.Default) {
            buildHighlightedText(code, language, highlightsBuilder)
        }.also { CodeHighlightCache.put(code, language, isDark, it) }
    }

    MarkdownCodeBackground(
        color = LocalMarkdownColors.current.codeBackground,
        shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        showHeader = showHeader,
        language = language,
        code = code,
    ) {
        MarkdownBasicText(
            text = highlighted,
            style = style,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(LocalMarkdownPadding.current.codeBlock),
        )
    }
}

private fun buildHighlightedText(
    code: String,
    language: String?,
    highlightsBuilder: Highlights.Builder,
): AnnotatedString {
    // The highlight engine itself may also throw (language parsing etc.); on failure fall back to plain text and never let it affect message rendering.
    val highlights = runCatching {
        val syntaxLanguage = language?.let { SyntaxLanguage.getByName(it) }
        highlightsBuilder.code(code)
            .let { if (syntaxLanguage != null) it.language(syntaxLanguage) else it }
            .build()
            .getHighlights()
    }.getOrDefault(emptyList())

    return buildAnnotatedString {
        append(code)
        for (highlight in highlights) {
            val start = highlight.location.start
            val end = highlight.location.end
            if (start < 0 || start >= end || end > code.length) continue
            val spanStyle = when (highlight) {
                is ColorHighlight -> SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            if (spanStyle != null) addStyle(spanStyle, start, end)
        }
    }
}

@Composable
private fun PlainMarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(color = color, lineHeight = 20.sp)
    )
}
