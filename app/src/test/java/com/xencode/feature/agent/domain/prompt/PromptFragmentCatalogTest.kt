package com.xencode.feature.agent.domain.prompt

import android.content.Context
import android.content.res.AssetManager
import com.xencode.feature.agent.domain.container.ContainerInstaller
import com.xencode.feature.workspace.domain.ProjectXencodeRoot
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Resolve/save/delete/reorder of four-tier source fragments: numeric identity, precedence, and the layer written to disk.
 *
 * Note: the local layer (`prompts/`) is only resolved when **a builtin with the same number exists** (see [PromptFragmentCatalog.resolve]),
 * so here assets exposes a builtin filename `10-builtin.md` to make the same-named local file take effect.
 */
class PromptFragmentCatalogTest {

    private lateinit var root: File
    private lateinit var xencodeDir: File
    private lateinit var workspaceDir: File
    private lateinit var catalog: PromptFragmentCatalog

    private val globalDir get() = File(xencodeDir, "prompts.custom")
    private val localDir get() = File(xencodeDir, "prompts")
    private val projectDir get() = File(File(workspaceDir, ".xencode"), "prompts.custom")

    @Before
    fun setUp() {
        root = Files.createTempDirectory("prompt-catalog-test").toFile()
        xencodeDir = File(root, "xencode").apply { mkdirs() }
        workspaceDir = File(root, "workspace").apply { mkdirs() }
        globalDir.mkdirs()
        localDir.mkdirs()

        val context = mockk<Context>()
        val assets = mockk<AssetManager>()
        every { context.assets } returns assets
        every { assets.list(any()) } returns arrayOf(BUILTIN_NAME)
        val installer = mockk<ContainerInstaller>()
        every { installer.xencodeDir } returns xencodeDir
        val projectRoot = mockk<ProjectXencodeRoot>()
        every { projectRoot.forPath(any()) } returns File(workspaceDir, ".xencode")

        catalog = PromptFragmentCatalog(context, installer, projectRoot)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(dir: File, name: String, content: String): File {
        dir.mkdirs()
        return File(dir, name).apply { writeText(content) }
    }

    @Test
    fun list_sameNumber_prefersHighestPriorityProjectLayer() {
        write(globalDir, "10-g.md", "G")
        write(localDir, BUILTIN_NAME, "L")
        write(projectDir, "10-p.md", "P")

        val fragment = catalog.list(workspaceDir.path).first { it.number == 10 }

        assertEquals(PromptFragmentSource.PROJECT, fragment.source)
        assertEquals("P", fragment.content)
    }

    @Test
    fun list_noProjectLayer_fallsBackToGlobalThenLocal() {
        write(localDir, BUILTIN_NAME, "L")
        write(globalDir, "10-g.md", "G")
        assertEquals(PromptFragmentSource.GLOBAL, catalog.list(workspaceDir.path).first { it.number == 10 }.source)

        globalDir.listFiles()!!.forEach { it.delete() }
        assertEquals(PromptFragmentSource.LOCAL, catalog.list(workspaceDir.path).first { it.number == 10 }.source)
    }

    @Test
    fun renderStatic_joinsBodiesInAscendingNumberOrder() {
        write(globalDir, "30-b.md", "BBB")
        write(globalDir, "10-a.md", "AAA")

        assertEquals("AAA\n\nBBB", catalog.renderStatic(workspaceDir.path))
    }

    @Test
    fun saveOverride_writesToTargetLayerAndClearsOldNumber() {
        catalog.saveOverride(12, "x", "X", workspaceDir.path, target = PromptFragmentSource.GLOBAL)
        assertTrue(File(globalDir, "12-x.md").isFile)

        catalog.saveOverride(
            20, "x", "X", workspaceDir.path,
            target = PromptFragmentSource.GLOBAL, previousNumber = 12
        )
        assertFalse("the old number should be cleaned up", File(globalDir, "12-x.md").exists())
        assertTrue(File(globalDir, "20-x.md").isFile)
    }

    @Test
    fun deleteOverride_deletesWritableOverride() {
        write(globalDir, "12-x.md", "X")
        assertTrue(catalog.deleteOverride(12, workspaceDir.path))
        assertFalse(File(globalDir, "12-x.md").exists())
    }

    @Test
    fun reorder_renumbersInNewOrderAndWritesWritableLayer() {
        write(globalDir, "10-a.md", "A")
        write(globalDir, "30-b.md", "B")

        val original = catalog.list(workspaceDir.path)
        val reversed = original.reversed()
        assertTrue(catalog.reorder(reversed, workspaceDir.path))

        val after = catalog.list(workspaceDir.path)
        assertEquals("B", after.first { it.number == 10 }.content)
        assertEquals("A", after.first { it.number == 30 }.content)
    }

    private companion object {
        /** The builtin filename (number 10) that makes the local layer resolvable. */
        const val BUILTIN_NAME = "10-builtin.md"
    }
}
