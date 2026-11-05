package com.xencode.feature.agent.domain.prompt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Fragment parsing rules for `prompts.custom/`: two-digit numeric identity, scan/sort/dedupe, `.no-builtin` detection, and static baseline merging.
 */
class PromptFragmentResolverTest {

    private val dir: File = Files.createTempDirectory("prompt-resolver-test").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun parseNumber_twoDigitPrefix_returnsValue() {
        assertEquals(0, PromptFragmentResolver.parseNumber("00-identity.md"))
        assertEquals(5, PromptFragmentResolver.parseNumber("05-my-rules.md"))
        assertEquals(50, PromptFragmentResolver.parseNumber("50-safety.md"))
        assertEquals(99, PromptFragmentResolver.parseNumber("99-any.md"))
    }

    @Test
    fun parseNumber_nonTwoDigitPrefix_returnsNull() {
        assertNull(PromptFragmentResolver.parseNumber("notes.md"))
        assertNull("single digit does not count", PromptFragmentResolver.parseNumber("0-x.md"))
        assertNull("four digits do not count", PromptFragmentResolver.parseNumber("2024-x.md"))
        assertNull("the name must not be empty", PromptFragmentResolver.parseNumber("10-.md"))
        assertNull("subdirectories do not count as top-level fragments", PromptFragmentResolver.parseNumber("agent/title-generator.md"))
        assertNull(PromptFragmentResolver.parseNumber(".no-builtin"))
    }

    @Test
    fun numberedFragments_ascendingByNumber_filtersNonMatchingFiles() {
        File(dir, "50-safety.md").writeText("a")
        File(dir, "05-intro.md").writeText("b")
        File(dir, "notes.md").writeText("c")
        File(dir, "agent").apply { mkdirs() }
        File(dir, "agent/title-generator.md").writeText("d")
        File(dir, ".no-builtin").writeText("")

        val result = PromptFragmentResolver.numberedFragments(dir)

        assertEquals(listOf(5, 50), result.map { it.first })
        assertTrue(result.all { it.second.isFile })
    }

    @Test
    fun numberedFragments_sameNumber_takesLexicographicallyFirst() {
        File(dir, "50-zzz.md").writeText("z")
        File(dir, "50-aaa.md").writeText("a")

        val result = PromptFragmentResolver.numberedFragments(dir)

        assertEquals(1, result.size)
        assertEquals("50-aaa.md", result.first().second.name)
    }

    @Test
    fun numberedFragments_missingDirectory_returnsEmpty() {
        assertTrue(PromptFragmentResolver.numberedFragments(File(dir, "missing")).isEmpty())
    }

    @Test
    fun isBuiltinDisabled_matchesExactNameOnly() {
        File(dir, ".DS_Store").writeText("x")
        assertFalse(PromptFragmentResolver.isBuiltinDisabled(dir))

        File(dir, ".no-builtin").writeText("")
        assertTrue(PromptFragmentResolver.isBuiltinDisabled(dir))
    }
}