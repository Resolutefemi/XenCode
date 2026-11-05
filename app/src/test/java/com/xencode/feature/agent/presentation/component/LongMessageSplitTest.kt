package com.xencode.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongMessageSplitTest {

    @Test
    fun `code fence longer than budget remains intact and is not sliced`() {
        val longCode = buildString {
            appendLine("```html")
            repeat(100) { i ->
                appendLine("  <div class=\"item-$i\">content line $i</div>")
            }
            appendLine("```")
        }
        assertTrue(longCode.length > 2000)

        val fullText = "Here is the code:\n\n$longCode\n\nPlease review."
        val slices = splitLongContent(fullText)

        // Verify the code block is treated as one intact unit and never sliced into fenceless fragments
        val codeSlice = slices.firstOrNull { it.trim().startsWith("```html") }
        assertTrue("must contain a chunk starting with ```html", codeSlice != null)
        assertTrue("that chunk must close with ```", codeSlice!!.trimEnd().endsWith("```"))
        assertTrue("that chunk must contain all 100 lines in full", codeSlice.contains("item-0") && codeSlice.contains("item-99"))
    }

    @Test
    fun `tilde code fence is also preserved intact`() {
        val longCode = buildString {
            appendLine("~~~python")
            repeat(80) { i ->
                appendLine("def func_$i(): pass")
            }
            appendLine("~~~")
        }
        val slices = splitLongContent(longCode)
        assertEquals(1, slices.size)
        assertTrue(slices[0].trim().startsWith("~~~python"))
        assertTrue(slices[0].trimEnd().endsWith("~~~"))
    }

    @Test
    fun `huge normal text block is sliced by lines to stay bounded`() {
        val hugeParagraph = buildString {
            repeat(50) { i ->
                appendLine("This is line $i of a plain paragraph, made long to exercise budget-based slicing.")
            }
        }
        val slices = splitLongContent(hugeParagraph)
        assertTrue("an oversized paragraph should be sliced into multiple chunks", slices.size > 1)
    }
}
