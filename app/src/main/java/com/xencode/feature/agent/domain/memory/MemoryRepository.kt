package com.xencode.feature.agent.domain.memory

import com.xencode.feature.agent.domain.container.ContainerInstaller
import com.xencode.feature.settings.data.repository.ExecutionModeHolder
import com.xencode.feature.workspace.domain.ProjectXencodeRoot
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val globalMemorySource: GlobalMemorySource,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller,
    private val projectXencodeRoot: ProjectXencodeRoot
) {
    /** Create the project-level source for the session's projectRoot (storage location decided by execution mode). */
    private fun projectSource(projectRoot: String) =
        ProjectMemorySource(projectRoot, executionModeHolder, containerInstaller, projectXencodeRoot)

    /** Scan and aggregate global and project-level memories. Same-named memories resolve to the project level. */
    fun listMemories(projectRoot: String?): List<Memory> {
        val allMemories = mutableListOf<Memory>()
        
        // 1. Load global memories
        allMemories.addAll(globalMemorySource.listMemories())
        
        // 2. Load project memories (if any)
        if (!projectRoot.isNullOrBlank()) {
            allMemories.addAll(projectSource(projectRoot).listMemories())
        }
        
        // Deduplicate: group by lowercase name and keep the last added (i.e. project level overrides global)
        return allMemories
            .groupBy { it.name.lowercase() }
            .map { it.value.last() }
    }

    /** Read the full instruction body of the given memory; null when missing or unparsable. */
    fun loadContent(name: String, projectRoot: String?): String? {
        // Prefer reading from the project level
        if (!projectRoot.isNullOrBlank()) {
            val content = projectSource(projectRoot).loadContent(name)
            if (content != null) return content
        }
        // Fall back to the global source
        return globalMemorySource.loadContent(name)
    }

    fun saveMemory(name: String, description: String, content: String, scope: MemoryScope, projectRoot: String?): Boolean {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.saveMemory(name, description, content)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).saveMemory(name, description, content)
            }
        }
    }

    fun editMemory(name: String, edits: List<MemoryEdit>, scope: MemoryScope, projectRoot: String?): MemoryEditResult {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.editMemory(name, edits)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) MemoryEditResult.Error("NO_WORKSPACE", "No workspace selected; project memory cannot be edited")
                else projectSource(projectRoot).editMemory(name, edits)
            }
        }
    }

    fun deleteMemory(name: String, scope: MemoryScope, projectRoot: String?): Boolean {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.deleteMemory(name)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).deleteMemory(name)
            }
        }
    }
}
