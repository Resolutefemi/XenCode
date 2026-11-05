package com.xencode.feature.settings.domain.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class StorageUsageScannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(dir: File, name: String, bytes: Int): File =
        File(dir, name).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes))
        }

    @Test
    fun sumsFilesRecursively() {
        val root = temp.newFolder("root")
        write(root, "a.txt", 100)
        write(File(root, "sub/deeper"), "b.bin", 400)

        assertEquals(500L, StorageUsageScanner.dirSize(root))
    }

    /** rootfs and node_modules are full of symlinks; following them double-counts or even loops forever. */
    @Test
    fun doesNotFollowSymlinks() {
        val root = temp.newFolder("root")
        val real = File(root, "real").apply { mkdirs() }
        write(real, "payload.bin", 1000)
        Files.createSymbolicLink(File(root, "link").toPath(), real.toPath())

        assertEquals(1000L, StorageUsageScanner.dirSize(root))
    }

    @Test
    fun symlinkTargetItselfIsNotCounted() {
        val root = temp.newFolder("root")
        val real = temp.newFolder("outside")
        write(real, "payload.bin", 700)
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), real.toPath())

        assertEquals(0L, StorageUsageScanner.dirSize(link))
    }

    @Test
    fun missingPathIsZero() {
        assertEquals(0L, StorageUsageScanner.dirSize(File(temp.root, "nope")))
    }

    @Test
    fun singleFileReturnsItsLength() {
        val file = write(temp.root, "single.bin", 321)
        assertEquals(321L, StorageUsageScanner.dirSize(file))
    }

    @Test
    fun cancellationStopsTraversalEarly() {
        val root = temp.newFolder("root")
        repeat(5) { write(root, "f$it.bin", 1000) }

        val total = StorageUsageScanner.dirSize(root, cancelled = { true })

        // Cancellation takes effect after the first file is visited, so at most one file is counted.
        assertTrue("cancelled early yet counted $total bytes", total <= 1000L)
    }
}
