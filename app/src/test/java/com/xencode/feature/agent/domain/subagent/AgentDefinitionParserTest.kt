package com.xencode.feature.agent.domain.subagent

import com.xencode.feature.agent.domain.model.AgentMode
import com.xencode.testutil.TestFileAccessProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentDefinitionParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val provider = TestFileAccessProvider()

    /**
     * Paths in the parser contract are **container paths** (POSIX separators, see the `"$base/${entry.name}"` built by [AgentDefinitionSource]);
     * the name fallback takes the basename by splitting on `/`. On Windows `absolutePath` uses backslashes, and passing it through directly fails to yield the file name,
     * so normalize it to a container path first.
     */
    private fun parseFile(file: File): AgentDefinition? =
        AgentDefinitionParser.parse(provider, file.absolutePath.replace(File.separatorChar, '/'))

    private fun write(name: String, content: String): File =
        File(tempFolder.root, name).apply { writeText(content) }

    @Test
    fun parse_fullFrontmatter() {
        val file = write(
            "researcher.md",
            """
            ---
            name: researcher
            description: read-only research
            provider: DeepSeek
            model: deepseek-reasoner
            reasoningEffort: high
            mode: plan
            tools: [readFile, search]
            disallowedTools: [Bash]
            inject: [base, projectRules]
            ---
            You are a research expert.
            """.trimIndent()
        )

        val def = parseFile(file)!!

        assertEquals("researcher", def.name)
        assertEquals("read-only research", def.description)
        assertEquals("DeepSeek", def.providerId)
        assertEquals("deepseek-reasoner", def.model)
        assertEquals("high", def.reasoningEffort)
        assertEquals(AgentMode.PLAN, def.mode)
        assertEquals(listOf("readFile", "search"), def.allowedTools)
        assertEquals(listOf("Bash"), def.disallowedTools)
        assertEquals(setOf(InjectPart.BASE, InjectPart.PROJECT_RULES), def.inject)
        assertEquals("You are a research expert.", def.prompt)
    }

    @Test
    fun parse_nameFallsBackToFileName() {
        val def = parseFile(write("coder.md", "---\ndescription: writes code\n---\nbody"))!!

        assertEquals("coder", def.name)
    }

    @Test
    fun parse_defaultsWhenInjectOmitted() {
        val def = parseFile(write("a.md", "---\nname: a\n---\nbody"))!!

        assertEquals(AgentDefinition.DEFAULT_INJECT, def.inject)
        assertTrue(def.allowedTools.isEmpty())
        assertTrue(def.disallowedTools.isEmpty())
        assertNull(def.providerId)
        assertNull(def.model)
        assertNull(def.reasoningEffort)
        assertNull(def.mode)
    }

    @Test
    fun parse_injectNoneMeansNoInjection() {
        val def = parseFile(write("a.md", "---\nname: a\ninject: [none]\n---\nbody"))!!

        assertTrue(def.inject.isEmpty())
    }

    /** Falls back to defaults when every value is invalid, so one misspelled word cannot drop all context. */
    @Test
    fun parse_invalidInjectFallsBackToDefault() {
        val def = parseFile(write("a.md", "---\nname: a\ninject: [bogus]\n---\nbody"))!!

        assertEquals(AgentDefinition.DEFAULT_INJECT, def.inject)
    }

    @Test
    fun parse_toolsAcceptsCommaSeparatedString() {
        val def = parseFile(
            write("a.md", "---\nname: a\ntools: readFile, search\n---\nbody")
        )!!

        assertEquals(listOf("readFile", "search"), def.allowedTools)
    }

    @Test
    fun parse_invalidReasoningEffortIgnored() {
        val def = parseFile(
            write("a.md", "---\nname: a\nreasoningEffort: turbo\n---\nbody")
        )!!

        assertNull(def.reasoningEffort)
        assertNull(def.mode)
    }

    @Test
    fun parse_blankPromptReturnsNull() {
        assertNull(parseFile(write("a.md", "---\nname: a\n---\n   \n")))
    }

    @Test
    fun parse_noFrontmatterTreatsWholeFileAsPrompt() {
        val def = parseFile(write("plain.md", "body only"))!!

        assertEquals("plain", def.name)
        assertEquals("body only", def.prompt)
    }

    @Test
    fun scan_onlyTopLevelMarkdownSortedByName() {
        write("b.md", "---\nname: b\n---\nbody")
        write("a.md", "---\nname: a\n---\nbody")
        write("notes.txt", "not a definition")
        File(tempFolder.root, "nested").mkdirs()
        File(File(tempFolder.root, "nested"), "c.md").writeText("---\nname: c\n---\nbody")

        val defs = AgentDefinitionDirectoryScanner.scan(provider, tempFolder.root.absolutePath)

        assertEquals(listOf("a", "b"), defs.map { it.name })
    }

    @Test
    fun scan_missingRootReturnsEmpty() {
        assertTrue(AgentDefinitionDirectoryScanner.scan(provider, File(tempFolder.root, "not-exists").absolutePath).isEmpty())
    }

    /**
     * The settings page persists via serialize, and reading it back must match what was stored exactly;
     * colons, hashes, and quotes in descriptions are YAML trouble spots, so they are deliberately included in the test data.
     */
    @Test
    fun serialize_roundTripsThroughParse() {
        val text = AgentDefinitionParser.serialize(
            name = "researcher",
            description = "read-only research: locate with search first, then verify with readFile; #1 priority; never modify files",
            providerId = "deepseek",
            model = "deepseek-reasoner",
            reasoningEffort = "high",
            mode = AgentMode.PLAN,
            allowedTools = listOf("readFile", "search"),
            disallowedTools = listOf("Bash"),
            inject = setOf(InjectPart.BASE, InjectPart.PROJECT_RULES),
            prompt = "You are a research expert.\n\nConclusions must cite file:line references."
        )

        val def = parseFile(write("researcher.md", text))!!

        assertEquals("researcher", def.name)
        assertEquals("read-only research: locate with search first, then verify with readFile; #1 priority; never modify files", def.description)
        assertEquals("deepseek", def.providerId)
        assertEquals("deepseek-reasoner", def.model)
        assertEquals("high", def.reasoningEffort)
        assertEquals(AgentMode.PLAN, def.mode)
        assertEquals(listOf("readFile", "search"), def.allowedTools)
        assertEquals(listOf("Bash"), def.disallowedTools)
        assertEquals(setOf(InjectPart.BASE, InjectPart.PROJECT_RULES), def.inject)
        assertEquals("You are a research expert.\n\nConclusions must cite file:line references.", def.prompt)
    }

    /** An empty inject set must serialize as [none]: omitting it would parse back as the default inject parts, exactly opposite to the user's choice. */
    @Test
    fun serialize_emptyInjectStaysEmptyAfterParse() {
        val text = AgentDefinitionParser.serialize(
            name = "bare",
            description = "",
            providerId = null,
            model = null,
            reasoningEffort = null,
            allowedTools = emptyList(),
            disallowedTools = emptyList(),
            inject = emptySet(),
            prompt = "Uses only its own prompt."
        )

        val def = parseFile(write("bare.md", text))!!

        assertTrue(def.inject.isEmpty())
        assertNull(def.providerId)
        assertNull(def.model)
        assertNull(def.reasoningEffort)
        assertNull(def.mode)
        assertTrue(def.allowedTools.isEmpty())
    }

    @Test
    fun isValidName_rejectsPathSeparatorsAndEmpty() {
        assertTrue(AgentDefinitionRepository.isValidName("researcher"))
        assertTrue(AgentDefinitionRepository.isValidName("代码审查"))
        assertFalse(AgentDefinitionRepository.isValidName(""))
        assertFalse(AgentDefinitionRepository.isValidName("  "))
        assertFalse(AgentDefinitionRepository.isValidName(".."))
        assertFalse(AgentDefinitionRepository.isValidName("a/b"))
        assertFalse(AgentDefinitionRepository.isValidName("a:b"))
        assertFalse(AgentDefinitionRepository.isValidName("a".repeat(41)))
    }
}
