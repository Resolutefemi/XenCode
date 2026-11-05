package com.xencode.feature.agent.domain.tool.skill

import com.xencode.feature.agent.domain.skill.Skill
import com.xencode.feature.agent.domain.skill.SkillRepository
import com.xencode.feature.agent.domain.tool.ToolResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LoadSkillTool] argument validation and repository delegation behavior.
 *
 * Covers the entry point of the 10-18 on-device crash: the process died after AI called loadSkill(re-analyze) —
 * the root cause was in the execution chain (full scan); the tool layer guarantees no regression in argument and error-path behavior.
 */
class LoadSkillToolTest {

    private val repository = mockk<SkillRepository>()
    private val tool = LoadSkillTool(repository)

    private fun args(name: String?): Map<String, JsonElement> =
        if (name == null) emptyMap() else mapOf("skill_name" to JsonPrimitive(name))

    @Test
    fun execute_returnsInstructionsFromRepository() = runBlocking {
        every { repository.loadInstructions("re-analyze") } returns "skill body"

        val result = tool.execute(args("re-analyze"))

        assertTrue(result is ToolResult.Success)
        assertEquals(
            "skill body",
            ((result as ToolResult.Success).data as JsonPrimitive).content
        )
    }

    @Test
    fun execute_missingNameReturnsError() = runBlocking {
        val result = tool.execute(args(null))

        assertTrue(result is ToolResult.Error)
        assertEquals("MISSING_SKILL_NAME", (result as ToolResult.Error).code)
    }

    @Test
    fun execute_blankNameReturnsError() = runBlocking {
        val result = tool.execute(args("   "))

        assertTrue(result is ToolResult.Error)
        assertEquals("MISSING_SKILL_NAME", (result as ToolResult.Error).code)
    }

    @Test
    fun execute_unknownSkillListsAvailableOnes() = runBlocking {
        every { repository.loadInstructions("nope") } returns null
        every { repository.listSkills() } returns listOf(
            Skill(name = "re-analyze", description = "reverse analysis", instructions = "x")
        )

        val result = tool.execute(args("nope"))

        assertTrue(result is ToolResult.Error)
        val error = result as ToolResult.Error
        assertEquals("SKILL_NOT_FOUND", error.code)
        assertTrue(error.message.contains("re-analyze"))
    }

    @Test
    fun execute_passesNameThrough() = runBlocking {
        every { repository.loadInstructions(any()) } returns "body"

        val result = tool.execute(args("Re-Analyze"))

        assertTrue(result is ToolResult.Success)
    }
}
