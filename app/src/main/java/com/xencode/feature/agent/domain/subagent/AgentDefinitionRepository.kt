package com.xencode.feature.agent.domain.subagent

import com.xencode.core.util.FileLogger
import com.xencode.feature.workspace.domain.FileAccessProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sub-agent definition repository, aggregating the global and project-level sources; for same-named definitions, project wins (consistent with skills and MCP two-level config).
 * Enable/disable state lives in [AgentDefinitionConfigRepository]: disabled definitions still appear in the settings list,
 * but never enter the main agent's dispatchable list and cannot be dispatched by [find].
 *
 * Global definitions live in `~/.xencode/agents`, project definitions in `<workspace>/.xencode/agents`; both are read/written via [FileAccessProvider]
 * with container paths, following the current execution environment (local host directory or remote SSH workspace).
 */
@Singleton
class AgentDefinitionRepository @Inject constructor(
    private val globalSource: GlobalDirectoryAgentSource,
    private val projectSource: ProjectDirectoryAgentSource,
    private val configRepository: AgentDefinitionConfigRepository,
    private val fileAccess: FileAccessProvider
) {
    /** All definitions (with source scope), not filtered by disabled state, sorted by name. */
    fun listAll(): List<AgentDefinitionEntry> =
        mergeAll(globalSource.listDefinitions(), projectSource.listDefinitions())

    /** Enabled definitions (for the main agent's dispatchable list). */
    fun listEnabled(): List<AgentDefinitionEntry> {
        val disabled = configRepository.disabledNames()
        return listAll().filterNot { it.definition.name.lowercase() in disabled }
    }

    /** Finds a dispatchable definition by name (case-insensitive); null when missing or disabled. */
    fun find(name: String): AgentDefinition? =
        listEnabled().firstOrNull { it.definition.name.equals(name, ignoreCase = true) }?.definition

    /**
     * Finds a definition by name, including disabled ones. Already-dispatched sub-sessions use it to restore their own prompt and tool set —
     * disabling should only block new dispatches, not swap the config under a running sub-agent mid-flight.
     */
    fun findIncludingDisabled(name: String): AgentDefinition? =
        listAll().firstOrNull { it.definition.name.equals(name, ignoreCase = true) }?.definition

    /** Whether this sub-agent is disabled in any scope. */
    fun isDisabled(name: String): Boolean = name.lowercase() in configRepository.disabledNames()

    /** Enables/disables a sub-agent in the given scope. */
    fun setDisabled(name: String, disabled: Boolean, scope: AgentDefinitionScope) =
        configRepository.setDisabled(name, disabled, scope)

    /**
     * Writes the definition file (create or edit). [originalName] is the pre-edit name, null on create;
     * a rename writes the new file then deletes the old one, equivalent to a rename. Returns null on success.
     */
    fun save(
        form: AgentDefinitionForm,
        scope: AgentDefinitionScope,
        originalName: String? = null
    ): AgentSaveError? {
        val name = form.name.trim()
        if (!isValidName(name)) return AgentSaveError.INVALID_NAME
        if (form.prompt.isBlank()) return AgentSaveError.EMPTY_PROMPT

        val overwritingSelf = originalName != null && originalName.equals(name, ignoreCase = true)
        if (!overwritingSelf) {
            val taken = listAll().any {
                it.scope == scope && it.definition.name.equals(name, ignoreCase = true)
            }
            if (taken) return AgentSaveError.NAME_CONFLICT
        }

        val existingFile = originalName?.let { old ->
            listAll().firstOrNull {
                it.scope == scope && it.definition.name.equals(old, ignoreCase = true)
            }?.definition?.filePath
        }

        val provider = fileAccess
        val root = agentsRoot(scope)
        val text = AgentDefinitionParser.serialize(
            name = name,
            description = form.description,
            providerId = form.providerId,
            model = form.model,
            reasoningEffort = form.reasoningEffort,
            mode = form.mode,
            allowedTools = form.allowedTools,
            disallowedTools = form.disallowedTools,
            inject = form.inject,
            prompt = form.prompt
        )

        return try {
            // When the name is unchanged, write back to the original file; never rebuild the path from name: the built-in Explore's file is explore.md
            // while its frontmatter says Explore, and rebuilding would create a duplicate Explore.md on case-sensitive file systems.
            val target = existingFile?.takeIf { overwritingSelf && provider.isFile(it) }
                ?: "${root.trimEnd('/')}/$name.md"
            provider.writeFile(target, text, overwrite = true)
            // Remove the old file after a rename, or an old-name duplicate with identical content remains
            if (!overwritingSelf && existingFile != null && existingFile != target && provider.isFile(existingFile)) {
                runCatching { provider.delete(existingFile) }.onFailure {
                    FileLogger.w(TAG, "Failed to delete old definition after rename: $existingFile")
                }
            }
            null
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to save sub-agent definition: $name", e)
            AgentSaveError.IO_FAILED
        }
    }

    /** Deletes the definition file in the given scope, unrecoverable. Returns whether it succeeded. */
    fun delete(name: String, scope: AgentDefinitionScope): Boolean {
        val entry = listAll().firstOrNull {
            it.definition.name.equals(name, ignoreCase = true) && it.scope == scope
        } ?: return false
        val filePath = entry.definition.filePath ?: return false
        val provider = fileAccess
        if (!provider.isFile(filePath)) return false
        return runCatching { provider.delete(filePath); true }.getOrDefault(false)
    }

    /** Definition directory for the given scope (container path). */
    fun agentsRoot(scope: AgentDefinitionScope): String =
        if (scope == AgentDefinitionScope.GLOBAL) globalSource.agentsRoot else projectSource.agentsRoot

    companion object {
        private const val TAG = "AgentDefinitionRepository"

        /** The name doubles as the file name, so path separators and reserved characters are banned; the length cap guards against extreme file names. */
        internal fun isValidName(name: String): Boolean {
            if (name.isBlank() || name.length > MAX_NAME_LENGTH) return false
            if (name == "." || name == "..") return false
            return name.none { it in ILLEGAL_NAME_CHARS || it.isISOControl() }
        }

        private const val MAX_NAME_LENGTH = 40
        private val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /** Merges the two levels: same-named project definitions override global, sorted by name. */
        internal fun mergeAll(
            global: List<AgentDefinition>,
            project: List<AgentDefinition>
        ): List<AgentDefinitionEntry> {
            val byName = LinkedHashMap<String, AgentDefinitionEntry>()
            global.forEach { byName[it.name.lowercase()] = AgentDefinitionEntry(it, AgentDefinitionScope.GLOBAL) }
            project.forEach { byName[it.name.lowercase()] = AgentDefinitionEntry(it, AgentDefinitionScope.PROJECT) }
            return byName.values.sortedBy { it.definition.name.lowercase() }
        }
    }
}
