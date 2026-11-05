package com.xencode.feature.agent.domain.checkpoint

import android.content.Context
import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.data.local.dao.CheckpointDao
import com.xencode.feature.agent.data.local.entity.CheckpointEntity
import com.xencode.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.xencode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CheckpointManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val checkpointDao: CheckpointDao,
    private val fileAccess: FileAccessProvider
) {
    // Checkpoint backup root path: <filesDir>/checkpoints/<sessionId>/<checkpointId>/
    private val baseCheckpointDir: File
        get() = File(context.filesDir, "checkpoints")

    // The active checkpoint must be isolated per session: multiple sessions (including a parent session and its subagents)
    // run workflows in parallel; a single field would be overwritten by later sessions, attaching file snapshots to another session,
    // and undoing in that session would restore/delete files this session just wrote.
    private val activeCheckpointIds = ConcurrentHashMap<String, String>()

    /**
     * Called when the user sends a new message; creates a new Checkpoint node
     */
    suspend fun createCheckpoint(
        sessionId: String,
        userMessageId: String,
        prompt: String
    ): CheckpointEntity = withContext(Dispatchers.IO) {
        val checkpointId = UUID.randomUUID().toString()
        val snippet = if (prompt.length > 60) prompt.take(60) + "..." else prompt
        val entity = CheckpointEntity(
            id = checkpointId,
            sessionId = sessionId,
            userMessageId = userMessageId,
            promptSnippet = snippet,
            createdAt = System.currentTimeMillis()
        )
        checkpointDao.insertCheckpoint(entity)
        activeCheckpointIds[sessionId] = checkpointId
        entity
    }

    fun setActiveCheckpointId(sessionId: String, checkpointId: String?) {
        if (checkpointId == null) {
            activeCheckpointIds.remove(sessionId)
        } else {
            activeCheckpointIds[sessionId] = checkpointId
        }
    }

    /**
     * Called before a tool (editFile/writeFile) is about to modify or create a file.
     * If the file has not been snapshotted in the current Checkpoint cycle, its original state is saved.
     */
    suspend fun beforeFileModified(
        sessionId: String,
        filePath: String
    ) = withContext(Dispatchers.IO) {
        val checkpointId = activeCheckpointIds[sessionId] ?: return@withContext

        // Dedup: within the same checkpointId, keep only the single most original snapshot of a file
        if (checkpointDao.countSnapshot(checkpointId, filePath) > 0) {
            return@withContext
        }

        val snapshotDir = File(baseCheckpointDir, "$sessionId/$checkpointId")
        if (!snapshotDir.exists()) {
            snapshotDir.mkdirs()
        }

        val exists = fileAccess.exists(filePath)
        val changeType = if (exists) "MODIFY" else "CREATE"
        val snapshotFileName = "${UUID.randomUUID()}_${File(filePath).name}"
        val snapshotFile = File(snapshotDir, snapshotFileName)

        if (changeType == "MODIFY") {
            val originalContent = fileAccess.readFile(filePath)
            snapshotFile.writeText(originalContent)
        } else {
            snapshotFile.writeText("") // Marks an empty creation record
        }

        val snapshotEntity = CheckpointFileSnapshotEntity(
            id = UUID.randomUUID().toString(),
            checkpointId = checkpointId,
            filePath = filePath,
            snapshotRelativePath = "$sessionId/$checkpointId/$snapshotFileName",
            changeType = changeType
        )
        checkpointDao.insertFileSnapshot(snapshotEntity)
        FileLogger.i(TAG, "Snapshot: session=$sessionId cp=$checkpointId type=$changeType path=$filePath")
    }

    /**
     * Rolls the code back to the initial state of the given Checkpoint node
     */
    suspend fun restoreCodeToCheckpoint(
        sessionId: String,
        targetCheckpointId: String
    ): Int = withContext(Dispatchers.IO) {
        val allCheckpoints = checkpointDao.getCheckpointsForSession(sessionId)
        val targetIndex = allCheckpoints.indexOfFirst { it.id == targetCheckpointId }
        if (targetIndex == -1) return@withContext 0

        // Collect snapshots of targetCheckpointId and every Checkpoint after it, then restore them in reverse order
        val checkpointsToRollback = allCheckpoints.subList(targetIndex, allCheckpoints.size).reversed()
        var restoredFileCount = 0
        FileLogger.i(
            TAG,
            "Restore start: session=$sessionId target=$targetCheckpointId covering ${checkpointsToRollback.size} checkpoints"
        )

        for (cp in checkpointsToRollback) {
            val snapshots = checkpointDao.getFileSnapshotsForCheckpoint(cp.id)
            for (snapshot in snapshots) {
                val snapshotFile = File(baseCheckpointDir, snapshot.snapshotRelativePath)

                if (snapshot.changeType == "MODIFY") {
                    if (snapshotFile.exists()) {
                        val content = snapshotFile.readText()
                        fileAccess.writeFile(snapshot.filePath, content, overwrite = true)
                        restoredFileCount++
                        FileLogger.i(TAG, "Restore overwrite: cp=${cp.id} path=${snapshot.filePath}")
                    }
                } else if (snapshot.changeType == "CREATE") {
                    // If the file was newly created, delete it safely during rollback
                    if (fileAccess.exists(snapshot.filePath)) {
                        fileAccess.delete(snapshot.filePath)
                        restoredFileCount++
                        FileLogger.i(TAG, "Restore delete: cp=${cp.id} path=${snapshot.filePath}")
                    }
                }
            }
        }
        FileLogger.i(TAG, "Restore done: session=$sessionId processed $restoredFileCount files in total")
        restoredFileCount
    }

    /**
     * Deletes all Checkpoint snapshots and records associated with the Session
     */
    suspend fun clearSessionCheckpoints(sessionId: String) = withContext(Dispatchers.IO) {
        activeCheckpointIds.remove(sessionId)
        checkpointDao.deleteFileSnapshotsForSession(sessionId)
        checkpointDao.deleteCheckpointsForSession(sessionId)
        val sessionDir = File(baseCheckpointDir, sessionId)
        if (sessionDir.exists()) {
            sessionDir.deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "CheckpointManager"
    }
}
