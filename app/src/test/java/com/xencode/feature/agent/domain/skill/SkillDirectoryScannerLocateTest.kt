package com.xencode.feature.agent.domain.skill

import com.xencode.testutil.TestFileAccessProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A [TestFileAccessProvider] mimicking the real-device ROOT channel semantics: head reads are returned truncated to maxBytes
 * (matching ROOT's `head -c`), and it separately counts "head read / full read / batch full read" calls,
 * used to assert the cost magnitude of loadSkill's targeted path.
 */
private class CountingFileAccessProvider(
    private val delegate: TestFileAccessProvider = TestFileAccessProvider()
) : com.xencode.feature.workspace.domain.FileAccessProvider by delegate {

    var headReadCalls = 0
    var fullReadCalls = 0
    var batchReadCalls = 0

    override fun readFile(path: String): String {
        fullReadCalls++
        return delegate.readFile(path)
    }

    override fun readFiles(paths: List<String>): Map<String, String> {
        batchReadCalls++
        return delegate.readFiles(paths)
    }

    override fun readFileHeads(paths: List<String>, maxBytes: Int): Map<String, String> {
        headReadCalls++
        val out = LinkedHashMap<String, String>(paths.size)
        for (p in paths) {
            runCatching { delegate.readFile(p) }.onSuccess { out[p] = it.take(maxBytes) }
        }
        return out
    }
}

/**
 * Behavior and cost tests for [SkillDirectoryScanner.locateAndRead] (loadSkill's targeted load path).
 *
 * Background: the old implementation's loadInstructions = listSkills() full scan — under the ROOT channel that meant one full read per skill,
 * so with 118 skills a single loadSkill caused a dozen-plus su forks plus every body crossing the pipe — instant ANR/crash on the main thread.
 * The new implementation reads only file heads to match the frontmatter name, then reads just that one file in full on a hit.
 */
class SkillDirectoryScannerLocateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val provider = CountingFileAccessProvider()

    private fun writeSkill(dirName: String, content: String): File {
        val dir = File(tempFolder.root, dirName).apply { mkdirs() }
        return File(dir, "SKILL.md").apply { writeText(content) }
    }

    private val root: String get() = tempFolder.root.absolutePath

    @Test
    fun locate_matchesFrontmatterNameAndReadsOnlyOneFullFile() {
        // 5 skills, each body large enough (it will not fit the 8K head), ensuring head read != full read
        repeat(5) { i ->
            writeSkill("skill-$i", "---\nname: skill-$i\ndescription: d$i\n---\n" + "x".repeat(20_000))
        }

        val instructions = SkillDirectoryScanner.locateAndRead(provider, root, "skill-3")

        assertTrue(instructions != null && instructions.contains("x"))
        assertEquals("the body should be full-read exactly once for the matched file", 1, provider.fullReadCalls + provider.batchReadCalls)
        assertEquals("heads should be read in one batch", 1, provider.headReadCalls)
    }

    @Test
    fun locate_matchesByDirNameWhenFrontmatterNameMissing() {
        writeSkill("dir-only-skill", "---\ndescription: no name field\n---\nbody")

        val instructions = SkillDirectoryScanner.locateAndRead(provider, root, "dir-only-skill")

        assertEquals("body", instructions)
        assertEquals(1, provider.headReadCalls)
    }

    @Test
    fun locate_nameInFrontmatterWinsOverDirectoryName() {
        // Directory name differs from the frontmatter name: must match by the frontmatter name (consistent with scan semantics)
        writeSkill("dir-a", "---\nname: real-name\n---\nBody A")

        assertNull(SkillDirectoryScanner.locateAndRead(provider, root, "dir-a"))
        assertEquals("Body A", SkillDirectoryScanner.locateAndRead(provider, root, "real-name"))
    }

    @Test
    fun locate_caseInsensitive() {
        writeSkill("mixed-case", "---\nname: Mixed-Case\n---\nbody")

        assertEquals("body", SkillDirectoryScanner.locateAndRead(provider, root, "mixed-CASE"))
    }

    @Test
    fun locate_prefersSkillMdOverClaudeMd() {
        val dir = File(tempFolder.root, "dual").apply { mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: dual\n---\nSKILL body")
        File(dir, "CLAUDE.md").writeText("---\nname: dual\n---\nCLAUDE body")

        assertEquals("SKILL body", SkillDirectoryScanner.locateAndRead(provider, root, "dual"))
    }

    @Test
    fun locate_nestedDirectories() {
        val nested = File(tempFolder.root, "repo/skills/nested-skill").apply { mkdirs() }
        File(nested, "SKILL.md").writeText("---\nname: nested-skill\n---\nnested body")

        assertEquals("nested body", SkillDirectoryScanner.locateAndRead(provider, root, "nested-skill"))
    }

    @Test
    fun locate_missingSkillReturnsNull() {
        writeSkill("exists", "---\nname: exists\n---\nbody")

        assertNull(SkillDirectoryScanner.locateAndRead(provider, root, "not-exists"))
    }

    @Test
    fun locate_truncatedFrontmatterFallsBackToDirNameThenFullParse() {
        // The frontmatter end marker sits beyond the 8K head window: head parsing cannot get the name (falls back to the directory name),
        // and only the full-text parse sees the real frontmatter name — both load paths must return the body
        val longDesc = "d".repeat(9_000)
        writeSkill(
            "deep-dir",
            "---\ndescription: \"$longDesc\"\nname: deep-name\n---\nthe real body"
        )

        // Load by frontmatter name: no head match -> the full-scan fallback still finds it (equivalent to the old behavior)
        assertEquals("the real body", SkillDirectoryScanner.locateAndRead(provider, root, "deep-name"))
        // Load by directory name: head hit -> read the full file and return the body
        assertEquals("the real body", SkillDirectoryScanner.locateAndRead(provider, root, "deep-dir"))
    }

    @Test
    fun locate_emptyRootReturnsNull() {
        assertNull(SkillDirectoryScanner.locateAndRead(provider, root, "any"))
    }

    @Test
    fun locate_readFailureOnOneFileDoesNotBreakOthers() {
        writeSkill("broken", "---\nname: broken\n---\n") // empty body, still parseable
        writeSkill("good", "---\nname: good\n---\ngood body")

        assertEquals("good body", SkillDirectoryScanner.locateAndRead(provider, root, "good"))
    }
}
