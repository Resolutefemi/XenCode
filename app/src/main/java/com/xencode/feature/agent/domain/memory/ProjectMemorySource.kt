package com.xencode.feature.agent.domain.memory

import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.container.ContainerInstaller
import com.xencode.feature.settings.data.repository.ExecutionMode
import com.xencode.feature.settings.data.repository.ExecutionModeHolder
import com.xencode.feature.workspace.domain.ProjectXencodeRoot
import java.io.File

/**
 * Project-level memory source. Created on the fly by the Repository (depends on the session's projectRoot).
 *
 * Local mode: stored under `<projectRoot>/.xencode/memory/` (travels with the workspace directory);
 * Remote mode: projectRoot is a remote server path that java.io.File cannot create locally (saving would always fail),
 * so memories are stored under the global config directory in `memory/projects/<project-name>-<identity-hash>/` (locally writable, stable across connections).
 * The identity hash is derived from "IP:port:path", so same-named paths on different servers (e.g. both /home/u/workspace/default)
 * land in different directories and never get mixed up.
 */
class ProjectMemorySource(
    private val projectRoot: String,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller,
    private val projectXencodeRoot: ProjectXencodeRoot
) : MemorySource {

    private val memoryRoot: File by lazy {
        if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) {
            File(File(containerInstaller.xencodeDir, "memory/projects"), projectXencodeRoot.projectKey(projectRoot))
        } else {
            File(projectRoot, ".xencode/memory")
        }
    }

    override fun listMemories(): List<Memory> {
        if (projectRoot.isBlank() || !memoryRoot.exists()) return emptyList()
        val files = memoryRoot.listFiles { file -> file.isFile && file.extension == "md" } ?: return emptyList()
        
        return files.mapNotNull { file -> MemoryParser.parse(file, MemoryScope.PROJECT) }
            .sortedBy { it.name.lowercase() }
    }

    override fun loadContent(name: String): String? {
        if (projectRoot.isBlank()) return null
        return listMemories()
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.content
    }

    override fun saveMemory(name: String, description: String, content: String): Boolean {
        if (projectRoot.isBlank()) return false
        return try {
            if (!memoryRoot.exists()) memoryRoot.mkdirs()
            val file = MemorySource.resolveMemoryFile(memoryRoot, name)
            file.writeText(MemoryParser.format(MemorySource.sanitizeName(name), description, content))
            true
        } catch (e: Exception) {
            FileLogger.e("ProjectMemorySource", "Failed to save memory: $name", e)
            false
        }
    }

    override fun deleteMemory(name: String): Boolean {
        if (projectRoot.isBlank()) return false
        val file = MemorySource.resolveMemoryFile(memoryRoot, name)
        return if (file.exists()) file.delete() else false
    }
}
