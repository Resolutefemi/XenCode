package com.xencode.feature.agent.domain.skill

import com.xencode.testutil.TestFileAccessProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillParserTest {

    private fun parseSerialized(
        name: String,
        description: String,
        requiredTools: List<String> = emptyList(),
        instructions: String
    ): Skill {
        val dir = java.nio.file.Files.createTempDirectory("skill-parser-test").toFile()
        return try {
            File(dir, "SKILL.md").writeText(
                SkillParser.serialize(
                    name = name,
                    description = description,
                    requiredTools = requiredTools,
                    instructions = instructions
                )
            )
            val parsed = SkillParser.parse(TestFileAccessProvider(), dir.absolutePath)
            assertNotNull(parsed)
            parsed!!
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun serialize_roundTripsThroughParse() {
        val skill = parseSerialized(
            name = "pdf-report",
            description = "Used when generating PDF reports",
            requiredTools = listOf("Bash", "writeFile"),
            instructions = "## Steps\n\n1. Read the template\n2. Render"
        )

        assertEquals("pdf-report", skill.name)
        assertEquals("Used when generating PDF reports", skill.description)
        assertEquals(listOf("Bash", "writeFile"), skill.requiredTools)
        assertEquals("## Steps\n\n1. Read the template\n2. Render", skill.instructions)
    }

    @Test
    fun serialize_keepsYamlValidWithSpecialChars() {
        val skill = parseSerialized(
            name = "tricky",
            description = "used for: handling \"quotes\" and # hash scenarios",
            instructions = "body"
        )

        assertEquals("used for: handling \"quotes\" and # hash scenarios", skill.description)
        assertEquals("body", skill.instructions)
    }

    @Test
    fun serialize_flattensMultilineDescription() {
        val skill = parseSerialized(
            name = "multi",
            description = "line one\nline two",
            instructions = "body"
        )

        assertEquals("line one line two", skill.description)
    }

    @Test
    fun serialize_omitsRequiredToolsWhenEmpty() {
        val text = SkillParser.serialize(
            name = "plain",
            description = "d",
            requiredTools = emptyList(),
            instructions = "body"
        )

        assertTrue(!text.contains("required_tools"))
    }
}
