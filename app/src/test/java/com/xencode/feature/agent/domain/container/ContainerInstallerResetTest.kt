package com.xencode.feature.agent.domain.container

import android.content.ContextWrapper
import com.xencode.feature.settings.data.repository.ExecutionMode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * rootfs deletion behavior when resetting a container.
 *
 * Regression point: reset used to recursively delete rootfs on the main thread; a container full of tools has on the order of a hundred thousand inodes, freezing the UI for tens of seconds until ANR.
 * Deletion now runs on the IO thread with progress reporting; the marker file is deleted first, so a mid-way process kill never leaves a container with a valid marker but incomplete contents.
 */
class ContainerInstallerResetTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "installer-test-${System.nanoTime()}")
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val cacheDir = File(root, "cache").apply { mkdirs() }
    private val context = FakeContext(filesDir, cacheDir)
    private val osDetector = ContainerOsDetector(context)
    private val installer = ContainerInstaller(context, osDetector)

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun resetRootfs_builtinContainer_directoryFullyDeletedOnReturn() = runTest {
        val rootfs = fakeRootfs(File(filesDir, "rootfs"), ".installed")

        installer.resetRootfs(ContainerProfile.BUILTIN_ALPINE)

        assertFalse("rootfs must be fully deleted by the time reset returns", rootfs.exists())
        assertFalse(installer.isInstalled())
    }

    @Test
    fun resetRootfs_reportsDeletedEntryCount() = runTest {
        fakeRootfs(File(filesDir, "rootfs"), ".installed")
        val reported = mutableListOf<Int>()

        installer.resetRootfs(ContainerProfile.BUILTIN_ALPINE) { reported += it }

        // rootfs itself + usr + usr/bin + busybox; the install marker is deleted beforehand and not counted
        assertEquals(4, reported.last())
    }

    @Test
    fun resetRootfs_customContainer_deletesOnlyOwnDirsAndClearsSystemCache() = runTest {
        val custom = localProfile("custom-1")
        val customRootfs = fakeRootfs(File(filesDir, "rootfs_custom-1"), ".installed_custom")
        val builtinRootfs = fakeRootfs(File(filesDir, "rootfs"), ".installed")
        osDetector.cacheOs("custom-1", "ubuntu")

        installer.resetRootfs(custom)

        assertFalse(customRootfs.exists())
        assertTrue("must not also wipe the built-in rootfs", builtinRootfs.exists())
        assertNull("rootfs is deleted, so the OS detection cache must be invalidated with it", osDetector.cachedOs("custom-1"))
    }

    @Test
    fun resetRootfs_remoteSshContainer_neverTouchesSameNamedLocalDir() = runTest {
        val remote = ContainerProfile(
            id = "custom-2",
            name = "remote",
            rootfsSource = RootfsSource.RemoteSsh("conn-1", "/home/u/workspace"),
            shellPath = null,
            isBuiltin = false,
            mode = ExecutionMode.REMOTE_SSH
        )
        val strayDir = fakeRootfs(File(filesDir, "rootfs_custom-2"), ".installed_custom")

        installer.resetRootfs(remote)

        assertTrue("a remote profile has no local rootfs and must not delete any local directory", strayDir.exists())
    }

    @Test
    fun resetRootfs_missingDirectory_returnsSilently() = runTest {
        installer.resetRootfs(localProfile("custom-3"))

        assertFalse(File(filesDir, "rootfs_custom-3").exists())
    }

    @Test
    fun resetRootfs_doesNotFollowSymlinksToDeleteTargets() = runTest {
        val outside = File(root, "outside").apply { mkdirs() }
        val keep = File(outside, "important.txt").apply { writeText("keep me") }
        val rootfs = fakeRootfs(File(filesDir, "rootfs"), ".installed")
        Files.createSymbolicLink(File(rootfs, "mnt").toPath(), outside.toPath())

        installer.resetRootfs(ContainerProfile.BUILTIN_ALPINE)

        assertFalse(rootfs.exists())
        assertTrue("the directory a symlink points to must not be deleted through the link", outside.exists())
        assertEquals("keep me", keep.readText())
    }

    @Test
    fun prootTmpDirFor_isolatedPerContainer_resettingBuiltinDoesNotAffectCustomTmp() = runTest {
        val custom = localProfile("custom-4")
        fakeRootfs(File(filesDir, "rootfs_custom-4"), ".installed_custom")
        File(filesDir, "rootfs_custom-4/tmp").mkdirs()
        fakeRootfs(File(filesDir, "rootfs"), ".installed")
        File(filesDir, "rootfs/tmp").mkdirs()

        installer.resetRootfs(ContainerProfile.BUILTIN_ALPINE)

        // PROOT_TMP_DIR used to be hardwired to the built-in rootfs's tmp; resetting the built-in container made every container fail with can't canonicalize.
        val customTmp = installer.prootTmpDirFor(custom)
        assertEquals(File(filesDir, "rootfs_custom-4/tmp"), customTmp)
        assertTrue("the custom container's PROOT_TMP_DIR must not vanish when the built-in container is reset", customTmp.isDirectory)
    }

    private fun localProfile(id: String) = ContainerProfile(
        id = id,
        name = id,
        rootfsSource = RootfsSource.LocalFile("file:///tmp/$id.tar.gz"),
        shellPath = null,
        isBuiltin = false
    )

    /** Builds a fake rootfs with some hierarchy, including the install marker. */
    private fun fakeRootfs(dir: File, marker: String): File {
        File(dir, "usr/bin").mkdirs()
        File(dir, "usr/bin/busybox").writeText("bin")
        File(dir, marker).writeText("alpine-3.21.3-v6")
        return dir
    }

    private class FakeContext(private val files: File, private val cache: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
    }
}
