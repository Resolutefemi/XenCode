package com.xencode.feature.workspace.domain

import com.xencode.feature.agent.domain.container.ContainerInstaller
import com.xencode.feature.settings.data.repository.ContainerSettingsRepository
import com.xencode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * WorkspacePathMapper: converting container paths ↔ real host paths both ways.
 * Fixed environment: host workspace=/data/ws, AI config dir=/data/xencode, rootfs=/data/rootfs, container home=/root.
 * activeProfileIdFlow uses emptyFlow() so the init coroutine's collect never fires, avoiding real DataStore calls
 * and the async profile switch (the extraBindings mount branch depends on that async update; racy and unstable in pure JVM, not covered here).
 */
class WorkspacePathMapperTest {

    private val workspaceRepository: WorkspaceRepository = mockk()
    private val containerInstaller: ContainerInstaller = mockk()
    private val containerSettingsRepository: ContainerSettingsRepository = mockk()
    private val pathHomeResolver: PathHomeResolver = mockk()

    private val wsRoot = "/data/ws"
    private val xencodeDir = "/data/xencode"
    private val rootfsDir = "/data/rootfs"
    private val home = "/root"

    private fun newMapper(): WorkspacePathMapper {
        every { workspaceRepository.currentPath() } returns wsRoot
        every { containerInstaller.xencodeDir } returns File(xencodeDir)
        every { containerInstaller.rootfsDirFor(any()) } returns File(rootfsDir)
        every { containerSettingsRepository.activeProfileIdFlow } returns emptyFlow()
        every { pathHomeResolver.home() } returns home
        // expandHome keeps real expansion semantics so the ~ prefix in toHostFile behaves exactly as in production
        every { pathHomeResolver.expandHome(any()) } answers {
            val p = firstArg<String>()
            when {
                p == "~" -> pathHomeResolver.home()
                p.startsWith("~/") -> pathHomeResolver.home().trimEnd('/') + p.removePrefix("~")
                else -> p
            }
        }
        return WorkspacePathMapper(workspaceRepository, containerInstaller, containerSettingsRepository, pathHomeResolver)
    }

    // ---------- toHostFile: container path → host file ----------

    @Test
    fun toHostFile_workspaceRootTilde_mapsToHostWorkspaceRoot() {
        assertEquals(File(wsRoot), newMapper().toHostFile("~/workspace"))
        assertEquals(File(wsRoot), newMapper().toHostFile("~/workspace/"))
    }

    @Test
    fun toHostFile_workspaceRootExpandedForm_mapsToHostWorkspaceRoot() {
        assertEquals(File(wsRoot), newMapper().toHostFile("$home/workspace"))
        assertEquals(File(wsRoot), newMapper().toHostFile("$home/workspace/"))
    }

    @Test
    fun toHostFile_workspaceChild_mapsUnderHostWorkspace() {
        assertEquals(File(wsRoot, "src/Main.kt"), newMapper().toHostFile("~/workspace/src/Main.kt"))
        assertEquals(File(wsRoot, "app/build.gradle.kts"), newMapper().toHostFile("$home/workspace/app/build.gradle.kts"))
    }

    @Test
    fun toHostFile_xencodeRoot_mapsToXencodeDir() {
        assertEquals(File(xencodeDir), newMapper().toHostFile("/root/.xencode"))
    }

    @Test
    fun toHostFile_xencodeChild_mapsUnderXencodeDir() {
        assertEquals(File(xencodeDir, "skills/x/SKILL.md"), newMapper().toHostFile("/root/.xencode/skills/x/SKILL.md"))
    }

    @Test
    fun toHostFile_otherAbsolutePath_mapsToRootfs() {
        assertEquals(File(rootfsDir, "etc/hosts"), newMapper().toHostFile("/etc/hosts"))
        // /root itself is not under the workspace/config-dir prefix; the generic absolute-path rule lands it in rootfs
        assertEquals(File(rootfsDir, "root"), newMapper().toHostFile("/root"))
    }

    @Test
    fun toHostFile_relativePath_hangsUnderWorkspaceRoot() {
        assertEquals(File(wsRoot, "src/Main.kt"), newMapper().toHostFile("src/Main.kt"))
    }

    @Test
    fun toHostFile_inputIsTrimmed() {
        assertEquals(File(wsRoot), newMapper().toHostFile("  ~/workspace  "))
    }

    // ---------- toContainerPath: host path → container path ----------

    @Test
    fun toContainerPath_workspaceRoot_mapsToContainerRoot() {
        assertEquals("~/workspace", newMapper().toContainerPath(wsRoot))
    }

    @Test
    fun toContainerPath_workspaceChild_mapsToContainerForm() {
        assertEquals("~/workspace/src/Main.kt", newMapper().toContainerPath("$wsRoot/src/Main.kt"))
    }

    @Test
    fun toContainerPath_expandedHomeForm_mapsToContainerForm() {
        assertEquals("~/workspace", newMapper().toContainerPath("$home/workspace"))
        assertEquals("~/workspace/a.txt", newMapper().toContainerPath("$home/workspace/a.txt"))
    }

    @Test
    fun toContainerPath_xencode_mapsToXencodeRootForm() {
        assertEquals("/root/.xencode", newMapper().toContainerPath(xencodeDir))
        assertEquals("/root/.xencode/skills/x/SKILL.md", newMapper().toContainerPath("$xencodeDir/skills/x/SKILL.md"))
    }

    @Test
    fun toContainerPath_rootfs_mapsToContainerAbsolute() {
        assertEquals("/", newMapper().toContainerPath(rootfsDir))
        assertEquals("/etc/hosts", newMapper().toContainerPath("$rootfsDir/etc/hosts"))
    }

    @Test
    fun toContainerPath_unmappedPath_unchanged() {
        assertEquals("/somewhere/else", newMapper().toContainerPath("/somewhere/else"))
    }
}
