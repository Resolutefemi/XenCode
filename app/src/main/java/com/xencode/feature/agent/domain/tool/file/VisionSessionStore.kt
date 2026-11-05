package com.xencode.feature.agent.domain.tool.file

import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.container.ContainerInstaller
import com.xencode.feature.agent.domain.model.AgentMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-disk storage for vision sessions: one JSON file per session, containing the full message history sent to the vision model (including image base64),
 * in the directory `~/.xencode/vision-sessions/`, styled after ToolOutputStore. When sessions exceed the cap, the least recently used are evicted by last-modified time.
 */
@Singleton
class VisionSessionStore @Inject constructor(
    private val containerInstaller: ContainerInstaller
) {
    private companion object {
        const val TAG = "VisionSessionStore"
        const val SESSION_DIR = "vision-sessions"
        const val MAX_SESSIONS = 100
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    /** Session file directory (host path). Externally used only for usage stats and cleanup. */
    val sessionDir: File get() = File(containerInstaller.xencodeDir, SESSION_DIR)

    private fun fileFor(id: String): File = File(sessionDir, "vision-$id.json")

    /** Loads the session history; returns null if the session does not exist or the file is corrupted. */
    suspend fun load(id: String): List<AgentMessage>? = mutex.withLock {
        val file = fileFor(id)
        if (!file.isFile) return null
        runCatching { json.decodeFromString<List<AgentMessage>>(file.readText()) }
            .onFailure { FileLogger.w(TAG, "Failed to read vision session $id: ${it.message}") }
            .getOrNull()
    }

    /** Overwrites the session history and triggers the eviction check. */
    suspend fun save(id: String, messages: List<AgentMessage>) = mutex.withLock {
        val file = fileFor(id)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(messages))
        evictIfNeeded()
    }

    /** Deletes a session (cleaned up when the first vision call fails, to avoid orphan files). */
    suspend fun delete(id: String) = mutex.withLock {
        fileFor(id).delete()
    }

    private fun evictIfNeeded() {
        val dir = sessionDir
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("vision-") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (files.size <= MAX_SESSIONS) return
        files.drop(MAX_SESSIONS).forEach { old ->
            FileLogger.i(TAG, "Evicting expired vision session: ${old.name}")
            old.delete()
        }
    }
}
