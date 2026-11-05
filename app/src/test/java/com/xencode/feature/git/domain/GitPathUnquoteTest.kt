package com.xencode.feature.git.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Restoring quoted paths from git output ([unquoteGitPath]).
 *
 * The focus is octal-escaped multibyte restoration: git outputs byte by byte as `\344\270\255`, and converting each
 * segment with `toChar()` yields three Latin-1 characters (the cause of garbled Chinese directory names in Git pages);
 * the bytes must be accumulated into a sequence and decoded as a whole as UTF-8.
 */
class GitPathUnquoteTest {

    @Test
    fun plainPath_returnedAsIs() {
        assertEquals("src/Main.kt", unquoteGitPath("src/Main.kt"))
        assertEquals("中文目录/文件.txt", unquoteGitPath("中文目录/文件.txt"))
    }

    @Test
    fun quotedPath_withSpace() {
        assertEquals("c d.txt", unquoteGitPath("\"c d.txt\""))
    }

    @Test
    fun quotedPath_escapedQuoteAndBackslash() {
        assertEquals("a\"b.txt", unquoteGitPath("\"a\\\"b.txt\""))
        assertEquals("a\\b.txt", unquoteGitPath("\"a\\\\b.txt\""))
    }

    @Test
    fun quotedPath_controlCharEscapes() {
        assertEquals("a\tb.txt", unquoteGitPath("\"a\\tb.txt\""))
        assertEquals("a\nb.txt", unquoteGitPath("\"a\\nb.txt\""))
        assertEquals("a\u0001b.txt", unquoteGitPath("\"a\\001b.txt\""))
    }

    @Test
    fun quotedPath_octalUtf8_decodedAsWholeSequence() {
        assertEquals("中文", unquoteGitPath("\"\\344\\270\\255\\346\\226\\207\""))
        assertEquals(
            "中文目录/文件.txt",
            unquoteGitPath("\"\\344\\270\\255\\346\\226\\207\\347\\233\\256\\345\\275\\225/\\346\\226\\207\\344\\273\\266.txt\"")
        )
    }

    @Test
    fun quotedPath_octalMixedWithAscii() {
        assertEquals("中 文.txt", unquoteGitPath("\"\\344\\270\\255 \\346\\226\\207.txt\""))
        assertEquals("中文\u0001名.txt", unquoteGitPath("\"中文\\001名.txt\""))
    }
}
