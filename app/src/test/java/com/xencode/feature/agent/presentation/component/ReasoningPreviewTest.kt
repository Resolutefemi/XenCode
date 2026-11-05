package com.xencode.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Single-line preview for the collapsible reasoning row: while thinking, take the last line (follows the scroll); after it ends, take the first line;
 * in both cases leading Markdown markers are stripped, and blank content returns an empty string (the caller falls back to a "thinking" label).
 */
class ReasoningPreviewTest {

    @Test
    fun live_takesLastLine() {
        val raw = "First check the directory layout\nthen confirm the entry file\nnow reading ChatMessageStyle.kt"
        assertEquals("now reading ChatMessageStyle.kt", reasoningPreviewLine(raw, live = true))
    }

    @Test
    fun live_ignoresTrailingNewline() {
        // The model just broke a line and has not written the next character yet: keep showing the previous line, not empty
        assertEquals("second line", reasoningPreviewLine("first line\nsecond line\n", live = true))
    }

    @Test
    fun finished_takesFirstNonBlankLine() {
        val raw = "\n## Analysis\nmuch more content follows"
        assertEquals("Analysis", reasoningPreviewLine(raw, live = false))
    }

    @Test
    fun stripsLeadingMarkdownMarkers() {
        assertEquals("heading", reasoningPreviewLine("# heading", live = false))
        assertEquals("point", reasoningPreviewLine("- **point**", live = false))
        assertEquals("quote", reasoningPreviewLine("> quote", live = false))
        assertEquals("code", reasoningPreviewLine("`code`", live = false))
    }

    @Test
    fun blankText_returnsEmpty() {
        assertEquals("", reasoningPreviewLine("", live = true))
        assertEquals("", reasoningPreviewLine("   \n  ", live = false))
    }
}
