package com.xencode.feature.agent.domain.skill

import com.xencode.testutil.TestFileAccessProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillDirectoryScannerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val provider = TestFileAccessProvider()

    @Test
    fun scan_parsesSkillWithFrontmatter() {
        val skillDir = tempFolder.newFolder("my-skill")
        File(skillDir, "SKILL.md").writeText(
            """
            ---
            name: my-skill
            description: my skill
            ---
            skill body
            """.trimIndent()
        )

        val skills = SkillDirectoryScanner.scan(provider, tempFolder.root.absolutePath)

        assertEquals(1, skills.size)
        assertEquals("my-skill", skills[0].name)
        assertEquals("my skill", skills[0].description)
        assertTrue(skills[0].instructions.contains("skill body"))
    }

    @Test
    fun scan_fallsBackToClaudeMd() {
        val skillDir = tempFolder.newFolder("legacy-skill")
        File(skillDir, "CLAUDE.md").writeText("---\nname: legacy-skill\n---\nbody")

        val skills = SkillDirectoryScanner.scan(provider, tempFolder.root.absolutePath)

        assertEquals(1, skills.size)
        assertEquals("legacy-skill", skills[0].name)
    }

    @Test
    fun scan_skipsInvalidDirectories() {
        val emptyDir = tempFolder.newFolder("no-instructions")
        File(emptyDir, "readme.txt").writeText("not a skill")

        assertTrue(SkillDirectoryScanner.scan(provider, tempFolder.root.absolutePath).isEmpty())
    }

    @Test
    fun scan_nestedDirectories() {
        val nested = tempFolder.newFolder("repo", "skills", "nested-skill")
        File(nested, "SKILL.md").writeText("---\nname: nested-skill\n---\nbody")

        val skills = SkillDirectoryScanner.scan(provider, tempFolder.root.absolutePath)

        assertEquals(1, skills.size)
        assertEquals("nested-skill", skills[0].name)
    }

    @Test
    fun scan_sortedByName() {
        tempFolder.newFolder("b-skill").let { File(it, "SKILL.md").writeText("---\nname: b-skill\n---\n") }
        tempFolder.newFolder("a-skill").let { File(it, "SKILL.md").writeText("---\nname: a-skill\n---\n") }

        val skills = SkillDirectoryScanner.scan(provider, tempFolder.root.absolutePath)

        assertEquals(listOf("a-skill", "b-skill"), skills.map { it.name })
    }

    @Test
    fun scan_missingRootReturnsEmpty() {
        assertTrue(SkillDirectoryScanner.scan(provider, File(tempFolder.root, "not-exists").absolutePath).isEmpty())
    }

    @Test
    fun scan_sameDirectoryWithBothFilesParsedOnce() {
        val skillDir = tempFolder.newFolder("dual-skill")
        File(skillDir, "SKILL.md").writeText("---\nname: dual-skill\n---\nSKILL body")
        File(skillDir, "CLAUDE.md").writeText("---\nname: dual-skill\n---\nCLAUDE body")

        val skills = SkillDirectoryScanner.scan(provider, tempFolder.root.absolutePath)

        assertEquals(1, skills.size)
    }
}
