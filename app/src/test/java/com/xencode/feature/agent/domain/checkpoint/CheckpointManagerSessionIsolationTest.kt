package com.xencode.feature.agent.domain.checkpoint

import android.content.ContextWrapper
import com.xencode.feature.agent.data.local.dao.CheckpointDao
import com.xencode.feature.agent.data.local.entity.CheckpointEntity
import com.xencode.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.xencode.feature.workspace.domain.FileAccessProvider
import com.xencode.feature.workspace.domain.FileEntry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Session ownership of the active checkpoint.
 *
 * Regression point: the active checkpoint used to be a single global field, so session B sending a message would overwrite session A's active checkpoint,
 * and snapshots from files A wrote afterwards were attributed to B; a single undo by B would restore or delete files A had already written to disk
 * (manifesting as a ghost file: "write succeeded but the file disappeared").
 */
class CheckpointManagerSessionIsolationTest {

    private val root: File = File(System.getProperty("java.io.tmpdir"), "cp-test-${System.nanoTime()}").apply { mkdirs() }
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val workspace = File(root, "workspace").apply { mkdirs() }

    private val dao = FakeCheckpointDao()
    private val fileAccess = LocalFileAccess()
    private val manager = CheckpointManager(FakeContext(filesDir), dao, fileAccess)

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun restoringOtherSession_doesNotRevertModifiedFile() = runTest {
        val path = File(workspace, "a.txt").apply { writeText("original") }.absolutePath

        manager.createCheckpoint("sessionA", "msgA", "request from A")
        val cpB = manager.createCheckpoint("sessionB", "msgB", "request from B")

        // A's agent touches the file only after B has sent a message
        manager.beforeFileModified("sessionA", path)
        fileAccess.writeFile(path, "A's new content", overwrite = true)

        val restored = manager.restoreCodeToCheckpoint("sessionB", cpB.id)

        assertEquals(0, restored)
        assertEquals("A's new content", File(path).readText())
    }

    @Test
    fun restoringOtherSession_doesNotDeleteCreatedFile() = runTest {
        val path = File(workspace, "new.txt").absolutePath

        manager.createCheckpoint("sessionA", "msgA", "request from A")
        val cpB = manager.createCheckpoint("sessionB", "msgB", "request from B")

        manager.beforeFileModified("sessionA", path)
        fileAccess.writeFile(path, "file created by A", overwrite = true)

        manager.restoreCodeToCheckpoint("sessionB", cpB.id)

        assertTrue(File(path).exists())
        assertEquals("file created by A", File(path).readText())
    }

    @Test
    fun restoringOwnSession_revertsAndDeletes() = runTest {
        val modified = File(workspace, "m.txt").apply { writeText("original") }.absolutePath
        val created = File(workspace, "c.txt").absolutePath

        val cpA = manager.createCheckpoint("sessionA", "msgA", "request from A")
        manager.createCheckpoint("sessionB", "msgB", "request from B")

        manager.beforeFileModified("sessionA", modified)
        fileAccess.writeFile(modified, "changed", overwrite = true)
        manager.beforeFileModified("sessionA", created)
        fileAccess.writeFile(created, "newly created", overwrite = true)

        val restored = manager.restoreCodeToCheckpoint("sessionA", cpA.id)

        assertEquals(2, restored)
        assertEquals("original", File(modified).readText())
        assertFalse(File(created).exists())
    }

    @Test
    fun snapshotsAreScopedToOwningSession() = runTest {
        val pathA = File(workspace, "a.txt").apply { writeText("a") }.absolutePath
        val pathB = File(workspace, "b.txt").apply { writeText("b") }.absolutePath

        val cpA = manager.createCheckpoint("sessionA", "msgA", "request from A")
        val cpB = manager.createCheckpoint("sessionB", "msgB", "request from B")

        manager.beforeFileModified("sessionA", pathA)
        manager.beforeFileModified("sessionB", pathB)

        assertEquals(listOf(pathA), dao.getFileSnapshotsForCheckpoint(cpA.id).map { it.filePath })
        assertEquals(listOf(pathB), dao.getFileSnapshotsForCheckpoint(cpB.id).map { it.filePath })
    }

    @Test
    fun clearingSessionDropsItsActiveCheckpoint() = runTest {
        val path = File(workspace, "a.txt").apply { writeText("original") }.absolutePath
        val cpA = manager.createCheckpoint("sessionA", "msgA", "request from A")

        manager.clearSessionCheckpoints("sessionA")
        manager.beforeFileModified("sessionA", path)

        assertTrue(dao.getFileSnapshotsForCheckpoint(cpA.id).isEmpty())
    }

    private class FakeContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    private class FakeCheckpointDao : CheckpointDao {
        private val checkpoints = mutableListOf<CheckpointEntity>()
        private val snapshots = mutableListOf<CheckpointFileSnapshotEntity>()

        override suspend fun insertCheckpoint(checkpoint: CheckpointEntity) {
            checkpoints.removeAll { it.id == checkpoint.id }
            checkpoints += checkpoint
        }

        override suspend fun insertFileSnapshot(snapshot: CheckpointFileSnapshotEntity) {
            snapshots.removeAll { it.id == snapshot.id }
            snapshots += snapshot
        }

        // Checkpoints created within the same millisecond sort stably by insertion order, matching Room's createdAt ASC
        override suspend fun getCheckpointsForSession(sessionId: String): List<CheckpointEntity> =
            checkpoints.filter { it.sessionId == sessionId }.sortedBy { it.createdAt }

        override suspend fun getCheckpointBySessionAndMessage(sessionId: String, messageId: String): CheckpointEntity? =
            checkpoints.firstOrNull { it.sessionId == sessionId && it.userMessageId == messageId }

        override suspend fun getCheckpointById(checkpointId: String): CheckpointEntity? =
            checkpoints.firstOrNull { it.id == checkpointId }

        override suspend fun getFileSnapshotsForCheckpoint(checkpointId: String): List<CheckpointFileSnapshotEntity> =
            snapshots.filter { it.checkpointId == checkpointId }

        override suspend fun countSnapshot(checkpointId: String, filePath: String): Int =
            snapshots.count { it.checkpointId == checkpointId && it.filePath == filePath }

        override suspend fun deleteCheckpointsForSession(sessionId: String) {
            checkpoints.removeAll { it.sessionId == sessionId }
        }

        override suspend fun deleteFileSnapshotsForSession(sessionId: String) {
            val ids = checkpoints.filter { it.sessionId == sessionId }.map { it.id }.toSet()
            snapshots.removeAll { it.checkpointId in ids }
        }

        override suspend fun deleteCheckpointsBefore(cutoffTimestamp: Long) {
            checkpoints.removeAll { it.createdAt < cutoffTimestamp }
        }
    }

    private class LocalFileAccess : FileAccessProvider {
        override fun readFile(path: String): String = File(path).readText()
        override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) {
            File(path).parentFile?.mkdirs()
            File(path).writeText(content, encoding)
        }

        override fun exists(path: String): Boolean = File(path).exists()
        override fun delete(path: String) {
            File(path).delete()
        }

        override fun readLines(path: String): Sequence<String> = throw UnsupportedOperationException()
        override fun isDirectory(path: String): Boolean = throw UnsupportedOperationException()
        override fun isFile(path: String): Boolean = throw UnsupportedOperationException()
        override fun fileSize(path: String): Long = throw UnsupportedOperationException()
        override fun lastModified(path: String): Long = throw UnsupportedOperationException()
        override fun permissions(path: String): String = throw UnsupportedOperationException()
        override fun listFiles(path: String): List<FileEntry> = throw UnsupportedOperationException()
        override fun readBytes(path: String): ByteArray = throw UnsupportedOperationException()
        override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
            File(path).parentFile?.mkdirs()
            File(path).writeBytes(bytes)
        }
        override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long = throw UnsupportedOperationException()
        override fun listFilesRecursive(path: String, maxDepth: Int): List<String> = emptyList()
        override fun copyToLocal(path: String): File = throw UnsupportedOperationException()
        override fun deleteRecursively(path: String) = throw UnsupportedOperationException()
        override fun rename(path: String, newPath: String) = throw UnsupportedOperationException()
        override fun copy(path: String, newPath: String, overwrite: Boolean) = throw UnsupportedOperationException()
        override fun move(path: String, newPath: String, overwrite: Boolean) = throw UnsupportedOperationException()
        override fun mkdirs(path: String) = throw UnsupportedOperationException()
        override fun parentPath(path: String): String? = throw UnsupportedOperationException()
        override fun toDisplayPath(path: String): String = path
    }
}
