package com.xencode.core.skills

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Skill discovery: frontmatter parsing, project/global roots, name safety. */
class SkillStoreTest {

    private fun skillDir(root: File, name: String, md: String): File {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "SKILL.md").writeText(md, Charsets.UTF_8)
        return dir
    }

    @Test
    fun listsProjectSkillsWithFrontmatter() {
        val ws = createTempDirectory(prefix = "xencode-skills").toFile()
        try {
            skillDir(
                File(File(ws, ".xencode"), "skills"), "commit-helper",
                "---\nname: commit-helper\ndescription: Writes conventional commits from the staged diff\n---\n" +
                    "Run `git diff --cached`, then propose a Conventional Commit message.\n"
            )
            skillDir(
                File(File(ws, ".xencode"), "skills"), "reviewer",
                "No frontmatter here — the folder name is the skill name.\n"
            )
            val store = SkillStore(ws)
            val skills = store.list()
            assertEquals(listOf("commit-helper", "reviewer"), skills.map { it.name })
            assertEquals("Writes conventional commits from the staged diff", skills[0].description)
            assertTrue(skills[0].body.startsWith("Run `git diff --cached`"))
            assertTrue(skills[0].body.contains("Conventional Commit"))
            assertEquals("(no description)", skills[1].description.ifBlank { "(no description)" })

            val loaded = store.load("commit-helper")!!
            assertEquals("commit-helper", loaded.name)
            assertNull(store.load("../escape"))
            assertNull(store.load("missing"))
        } finally {
            ws.deleteRecursively()
        }
    }

    @Test
    fun globalSkillsAreFoundButProjectWinsByName() {
        val ws = createTempDirectory(prefix = "xencode-skills2").toFile()
        val fakeHome = createTempDirectory(prefix = "xencode-skills-home").toFile()
        val oldHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", fakeHome.path)
            skillDir(
                File(File(fakeHome, ".xencode"), "skills"), "shared",
                "---\ndescription: global version\n---\nglobal body"
            )
            skillDir(
                File(File(ws, ".xencode"), "skills"), "shared",
                "---\ndescription: project version\n---\nproject body"
            )
            skillDir(
                File(File(fakeHome, ".xencode"), "skills"), "only-global",
                "---\ndescription: from the home folder\n---\nglobal"
            )
            val skills = SkillStore(ws).list()
            assertEquals(listOf("only-global", "shared"), skills.map { it.name })
            assertEquals("project version", skills.first { it.name == "shared" }.description)
            assertEquals("global", SkillStore(ws).load("only-global")!!.body)
        } finally {
            System.setProperty("user.home", oldHome)
            ws.deleteRecursively()
            fakeHome.deleteRecursively()
        }
    }

    @Test
    fun frontmatterParserHandlesMissingAndMalformedBlocks() {
        val (empty, body1) = SkillStore.frontmatter("Just a body.")
        assertTrue(empty.isEmpty())
        assertEquals("Just a body.", body1)
        val (meta, body2) = SkillStore.frontmatter("---\nname: a\ndescription: b\n---\nBody line.\n")
        assertEquals(mapOf("name" to "a", "description" to "b"), meta)
        assertEquals("Body line.", body2.trim())
        // Unterminated frontmatter: everything is body.
        val (none, body3) = SkillStore.frontmatter("---\nname: x\nbody continues")
        assertTrue(none.isEmpty())
        assertTrue(body3.startsWith("---"))
    }
}
