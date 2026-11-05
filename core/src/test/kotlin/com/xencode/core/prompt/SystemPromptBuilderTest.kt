package com.xencode.core.prompt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.io.File

/** System prompt builder: rules-file precedence and content inclusion. */
class SystemPromptBuilderTest {

    @TempDir
    lateinit var tmp: File

    @Test
    fun prefersXencodeMdOverClaudeMdOverAgentsMd() {
        assertEquals(null, SystemPromptBuilder.projectRulesPath(tmp.absolutePath))
        File(tmp, "AGENTS.md").writeText("agents rules")
        assertEquals("AGENTS.md", File(SystemPromptBuilder.projectRulesPath(tmp.absolutePath)).name)
        File(tmp, "CLAUDE.md").writeText("claude rules")
        assertEquals("CLAUDE.md", File(SystemPromptBuilder.projectRulesPath(tmp.absolutePath)).name)
        File(tmp, "XENCODE.md").writeText("xencode rules")
        assertEquals("XENCODE.md", File(SystemPromptBuilder.projectRulesPath(tmp.absolutePath)).name)
    }

    @Test
    fun buildIncludesEnvironmentToolsAndRules() {
        File(tmp, "XENCODE.md").writeText("- always write tests first")
        val prompt = SystemPromptBuilder.build(tmp.absolutePath, listOf("read_file", "run_command", "mcp_s_t"))
        assertTrue(prompt.contains("You are XenCode"), prompt)
        assertTrue(prompt.contains(tmp.absolutePath), prompt)
        assertTrue(prompt.contains("read_file, run_command, mcp_s_t"), prompt)
        assertTrue(prompt.contains("Project rules (XENCODE.md)"), prompt)
        assertTrue(prompt.contains("always write tests first"), prompt)
        assertTrue(prompt.contains("OS:"), prompt)
        assertTrue(prompt.contains("Date:"), prompt)
    }

    @Test
    fun buildClipsOversizedRules() {
        File(tmp, "XENCODE.md").writeText("x".repeat(10_000))
        val prompt = SystemPromptBuilder.build(tmp.absolutePath, emptyList())
        assertTrue(prompt.contains("rules clipped"), prompt)
        assertTrue(prompt.length < 12_000)
    }
}
