package com.xencode.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPreprocessorTest {

    private fun process(s: String) = MarkdownPreprocessor.process(s)

    @Test
    fun `plain text unchanged`() {
        val t = "Plain text with no special markers."
        assertEquals(t, process(t))
    }

    @Test
    fun `inline math becomes math image link`() {
        val out = process("The mass-energy equation \$E = mc^2\$ is famous")
        assertTrue(out.contains(MarkdownPreprocessor.MATH_INLINE_SCHEME))
        val link = Regex("""!\[]\((xencode-math-inline://[^)]+)\)""").find(out)!!.groupValues[1]
        val (latex, block) = MarkdownPreprocessor.decodeMathLink(link)!!
        assertEquals("E = mc^2", latex)
        assertFalse(block)
    }

    @Test
    fun `block math becomes block math image link`() {
        val out = process("Formula:\n\$\$\\int_a^b f(x)dx\$\$\nend")
        val link = Regex("""!\[]\((xencode-math-block://[^)]+)\)""").find(out)!!.groupValues[1]
        val (latex, block) = MarkdownPreprocessor.decodeMathLink(link)!!
        assertEquals("\\int_a^b f(x)dx", latex)
        assertTrue(block)
    }

    @Test
    fun `math inside code fence is not extracted`() {
        val src = "```\ncost = \$5 and \$10\n```"
        assertEquals(src, process(src))
    }

    @Test
    fun `math inside inline code is not extracted`() {
        val out = process("inline `\$x\$` is left alone")
        assertTrue(out.contains("`\$x\$`"))
        assertFalse(out.contains(MarkdownPreprocessor.MATH_INLINE_SCHEME))
    }

    @Test
    fun `latex paren inline math becomes math image link`() {
        val out = process("""Let \(S=a+b+c\). The algorithm only needs to enumerate the smaller factor \(b\):""")
        assertTrue(out.contains(MarkdownPreprocessor.MATH_INLINE_SCHEME))
        val matches = Regex("""!\[]\((xencode-math-inline://[^)]+)\)""").findAll(out).toList()
        assertEquals(2, matches.size)
        val first = MarkdownPreprocessor.decodeMathLink(matches[0].groupValues[1])!!
        assertEquals("S=a+b+c", first.first)
        assertFalse(first.second)
        val second = MarkdownPreprocessor.decodeMathLink(matches[1].groupValues[1])!!
        assertEquals("b", second.first)
        assertFalse(second.second)
    }

    @Test
    fun `latex bracket block math becomes block math image link`() {
        val out = process("""Formula:\[\sum_{i=1}^n i = \frac{n(n+1)}{2}\]end""")
        assertTrue(out.contains(MarkdownPreprocessor.MATH_BLOCK_SCHEME))
        val link = Regex("""!\[]\((xencode-math-block://[^)]+)\)""").find(out)!!.groupValues[1]
        val (latex, block) = MarkdownPreprocessor.decodeMathLink(link)!!
        assertEquals("""\sum_{i=1}^n i = \frac{n(n+1)}{2}""", latex)
        assertTrue(block)
    }

    @Test
    fun `html tags are kept as literal text`() {
        assertEquals("<\u200Bb>bold<\u200B/b>", process("<b>bold</b>"))
        assertEquals("<\u200Bi>italic<\u200B/i>", process("<i>italic</i>"))
        assertEquals("<\u200Bstrong>strong<\u200B/strong>", process("<strong>strong</strong>"))
    }

    @Test
    fun `br tag is kept as literal text`() {
        assertEquals("line one<\u200Bbr>line two", process("line one<br>line two"))
    }

    @Test
    fun `sub and sup tags are kept as literal text`() {
        assertEquals("H<\u200Bsub>2<\u200B/sub>O", process("H<sub>2</sub>O"))
        assertEquals("mv<\u200Bsup>2<\u200B/sup>/2", process("mv<sup>2</sup>/2"))
    }

    @Test
    fun `inline tags are kept as literal text`() {
        assertEquals(
            "<\u200Bspan style=\"color:red\">red text<\u200B/span>",
            process("""<span style="color:red">red text</span>"""),
        )
    }

    @Test
    fun `block tags are kept as literal text`() {
        assertEquals("<\u200Bhr>", process("<hr>"))
        assertEquals("<\u200Bul><\u200Bli>one<\u200B/li><\u200Bli>two<\u200B/li><\u200B/ul>", process("<ul><li>one</li><li>two</li></ul>"))
        assertEquals("<\u200Bblockquote>quoted content<\u200B/blockquote>", process("<blockquote>quoted content</blockquote>"))
        assertEquals("<\u200Bimg src=\"test.png\" alt=\"test image\">", process("""<img src="test.png" alt="test image">"""))
        assertEquals("<\u200Ba href=\"https://x.com\">click here<\u200B/a>", process("""<a href="https://x.com">click here</a>"""))
    }

    @Test
    fun `html entities are decoded`() {
        // `&lt;` is first decoded to `<`, then neutralizeHtmlOpenBracket inserts a zero-width space
        assertEquals("a <\u200B b & c > d", process("a &lt; b &amp; c &gt; d"))
    }

    @Test
    fun `html tags inside code fence are untouched`() {
        val src = "```html\n<b>not bold</b>\n```"
        assertEquals(src, process(src))
    }
}
