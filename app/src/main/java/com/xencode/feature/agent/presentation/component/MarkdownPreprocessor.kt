package com.xencode.feature.agent.presentation.component

import org.jsoup.parser.Parser
import java.util.Base64

/**
 * Lightweight preprocessing done before handing text to the Markdown renderer:
 *
 * 1. Extract LaTeX math (`$$...$$` block, `$...$` inline), encoding it as Markdown image links with a special scheme,
 *    rendered by [MathImageTransformer] via jlatexmath.
 * 2. Decode HTML entities and show inline HTML tags as literal text — the chat stream does not render HTML,
 *    nor map HTML→Markdown syntax; leaving tags visible as-is is fine (see the ZWSP handling in [processNormal]).
 *
 * All processing skips fenced code blocks and inline code, to avoid breaking `$` / `<` and similar characters in code samples.
 */
internal object MarkdownPreprocessor {

    // Process-wide result cache: process is called every time an item scrolls into the viewport (the caller only has remember(text), and
    // remember resets once scrolled out and disposed). Text with math delimiters runs regexes; re-running repeatedly during a fast fling slows the main thread.
    // This process-wide LRU keyed by the raw text processes each snippet only once.
    private const val CACHE_MAX = 256
    private val cache = object : LinkedHashMap<String, String>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > CACHE_MAX
    }

    fun process(raw: String): String {
        if (raw.isEmpty()) return raw
        // Fast path needing no processing: no HTML tags, no $ or \( / \[ math delimiters, and no & entities
        if (!raw.contains('<') && !raw.contains('$') && !raw.contains('\\') && !raw.contains('&')) return raw

        synchronized(cache) { cache[raw] }?.let { return it }

        val sb = StringBuilder(raw.length + 32)
        for (seg in splitPreservingCode(raw)) {
            when (seg.kind) {
                SegmentKind.NORMAL -> sb.append(processNormal(seg.text))
                // Inline code is also AST-traversed by the renderer (child nodes of CODE_SPAN), and HTML_TAG is likewise dropped,
                // so it gets the same `<` neutralization as normal text; fenced code blocks render verbatim and stay untouched.
                SegmentKind.INLINE_CODE -> sb.append(neutralizeHtmlOpenBracket(seg.text))
                SegmentKind.FENCE -> sb.append(seg.text)
            }
        }
        val result = sb.toString()
        synchronized(cache) { cache[raw] = result }
        return result
    }

    private enum class SegmentKind { NORMAL, INLINE_CODE, FENCE }

    private data class Segment(val kind: SegmentKind, val text: String)

    /** Scans the text, splitting fenced code blocks and inline code into code segments kept verbatim; the rest becomes normal text segments. */
    private fun splitPreservingCode(text: String): List<Segment> {
        val result = ArrayList<Segment>()
        val normal = StringBuilder()
        val n = text.length
        var i = 0
        var atLineStart = true

        fun flushNormal() {
            if (normal.isNotEmpty()) {
                result.add(Segment(SegmentKind.NORMAL, normal.toString()))
                normal.clear()
            }
        }

        while (i < n) {
            val c = text[i]

            // Fenced code block: >=3 consecutive ` or ~ at line start
            if (atLineStart && (c == '`' || c == '~')) {
                var j = i
                while (j < n && text[j] == c) j++
                if (j - i >= 3) {
                    val fenceLen = j - i
                    var lineEnd = j
                    while (lineEnd < n && text[lineEnd] != '\n') lineEnd++
                    var end = n
                    var lineStart = if (lineEnd < n) lineEnd + 1 else n
                    var k = lineStart
                    while (k <= n) {
                        if (k == n || text[k] == '\n') {
                            var p = lineStart
                            while (p < k && text[p] == ' ') p++
                            var q = p
                            while (q < k && text[q] == c) q++
                            if (q - p >= fenceLen) {
                                var rest = q
                                while (rest < k && text[rest] == ' ') rest++
                                if (rest == k) { end = k; break }
                            }
                            lineStart = k + 1
                        }
                        k++
                    }
                    flushNormal()
                    result.add(Segment(SegmentKind.FENCE, text.substring(i, end)))
                    i = end
                    atLineStart = i < n && text[i] == '\n'
                    continue
                }
            }

            // Inline code: wrapped by a same-length ` sequence, never crossing lines
            if (c == '`') {
                var j = i
                while (j < n && text[j] == '`') j++
                val tickLen = j - i
                var k = j
                var found = -1
                while (k < n && text[k] != '\n') {
                    if (text[k] == '`') {
                        var m = k
                        while (m < n && text[m] == '`') m++
                        if (m - k == tickLen) { found = m; break }
                        k = m
                    } else k++
                }
                if (found >= 0) {
                    flushNormal()
                    result.add(Segment(SegmentKind.INLINE_CODE, text.substring(i, found)))
                    i = found
                    atLineStart = false
                    continue
                }
            }

            normal.append(c)
            atLineStart = c == '\n'
            i++
        }
        flushNormal()
        return result
    }

    private fun processNormal(input: String): String {
        var s = extractMath(input)
        s = Parser.unescapeEntities(s, false)
        return neutralizeHtmlOpenBracket(s)
    }

    /**
     * The chat stream does not render HTML: insert a zero-width space (U+200B) after `<` so tags are not parsed into HTML_TAG nodes.
     * The renderer has no handling branch for HTML_TAG (drops it outright); with the character inserted, `<` degrades to an ordinary LT token and displays as-is,
     * so `<b>` shows as the literal text `<b>`. ZWSP rather than U+2060 — the latter renders as tofu blocks in some device fonts.
     */
    private fun neutralizeHtmlOpenBracket(s: String): String = s.replace("<", "<\u200B")

    // ---------------------------------------------------------------------------------------------
    // Math formulas
    // ---------------------------------------------------------------------------------------------

    private val BLOCK_MATH_DOLLAR = Regex("""\$\$(.+?)\$\$""", RegexOption.DOT_MATCHES_ALL)
    private val BLOCK_MATH_BRACKET = Regex("""\\\[(.+?)\\\]""", RegexOption.DOT_MATCHES_ALL)

    // Inline: non-whitespace at both inner edges of the delimiters, content without newlines or $, avoiding false hits on currency like "$5 ... $10" (non-whitespace anchors)
    private val INLINE_MATH_DOLLAR = Regex("""\$(?=\S)([^\n$]*?\S)\$""")
    // LaTeX standard inline delimiters \( ... \)
    private val INLINE_MATH_PAREN = Regex("""\\\((.+?)\\\)""", RegexOption.DOT_MATCHES_ALL)

    private fun extractMath(input: String): String {
        var s = input
        if (s.contains("$$")) {
            s = BLOCK_MATH_DOLLAR.replace(s) { m ->
                "\n\n" + encodeMathLink(m.groupValues[1].trim(), block = true) + "\n\n"
            }
        }
        if (s.contains("\\[")) {
            s = BLOCK_MATH_BRACKET.replace(s) { m ->
                "\n\n" + encodeMathLink(m.groupValues[1].trim(), block = true) + "\n\n"
            }
        }
        if (s.contains("\\(")) {
            s = INLINE_MATH_PAREN.replace(s) { m ->
                encodeMathLink(m.groupValues[1].trim(), block = false)
            }
        }
        if (s.contains('$')) {
            s = INLINE_MATH_DOLLAR.replace(s) { m ->
                encodeMathLink(m.groupValues[1].trim(), block = false)
            }
        }
        return s
    }

    // ---------------------------------------------------------------------------------------------
    // Math link encode/decode (used by MathImageTransformer)
    // ---------------------------------------------------------------------------------------------

    const val MATH_BLOCK_SCHEME = "xencode-math-block://"
    const val MATH_INLINE_SCHEME = "xencode-math-inline://"

    fun encodeMathLink(latex: String, block: Boolean): String {
        val enc = Base64.getUrlEncoder().withoutPadding().encodeToString(latex.toByteArray(Charsets.UTF_8))
        return "![](${if (block) MATH_BLOCK_SCHEME else MATH_INLINE_SCHEME}$enc)"
    }

    /** Parses a math link; returns null for non-math links. Returns (latex, isBlock). */
    fun decodeMathLink(link: String): Pair<String, Boolean>? {
        val block = link.startsWith(MATH_BLOCK_SCHEME)
        val inline = link.startsWith(MATH_INLINE_SCHEME)
        if (!block && !inline) return null
        val enc = link.removePrefix(if (block) MATH_BLOCK_SCHEME else MATH_INLINE_SCHEME)
        return try {
            val latex = String(Base64.getUrlDecoder().decode(enc), Charsets.UTF_8)
            latex to block
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
