package com.xencode.feature.agent.domain.subagent

import com.xencode.core.util.FileLogger
import com.xencode.core.watch.FileChange
import com.xencode.core.watch.FileChangeHub
import com.xencode.feature.agent.domain.container.ContainerInstaller
import com.xencode.feature.workspace.domain.FileAccessProvider
import com.xencode.feature.workspace.domain.ProjectXencodeRoot
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * Persists sub-agent enable/disable config under the same conventions as skills' `skills.json`, at two levels, global + project:
 * - Global: the current execution environment's `~/.xencode/agents.json` (kept across projects);
 * - Project: `workspacePath/.xencode/agents.json` (travels with the workspace, git-trackable).
 *
 * Format `{"disabled": ["name-a"]}`, storing only the disabled list; the effective disabled set = global + project union.
 * Every read loads from disk, so manual external edits take effect immediately; sub-agent names in the list that no longer exist are naturally ignored during filtering.
 */
@Singleton
class AgentDefinitionConfigRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val projectXencodeRoot: ProjectXencodeRoot,
    private val fileChangeHub: FileChangeHub,
    private val fileAccess: FileAccessProvider
) {
    /** Local global config file, used only for host file-change watching. */
    private fun globalFile(): File = File(containerInstaller.xencodeDir, CONFIG_FILE)

    private fun projectFile(): File = File(projectXencodeRoot.current(), CONFIG_FILE)

    // ── External change watching: after sub-agent directories or agents.json are added/removed/modified directly in the container or by hand, the UI is notified to refresh within seconds ──

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Broadcast once when sub-agent directories or config files are modified externally. Subscription-driven: only while there are subscribers (the settings page) does
     * [FileChangeHub] watch the sub-agent directories and both agents.json files; zero overhead with no subscribers.
     */
    val changes: SharedFlow<Unit> = merge(
        fileChangeHub.watchXencode(AGENTS_DIR, recursive = true),
        fileChangeHub.watchXencode(),
        fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$XENCODE_DIR/$AGENTS_DIR", recursive = true),
        fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$XENCODE_DIR")
    ).mapNotNull { batch ->
        if (!batch.changes.any(::isAgentChange)) return@mapNotNull null
        FileLogger.i(TAG, "Sub-agent directory or config change detected; refresh notified")
        Unit
    }.shareIn(watchScope, SharingStarted.WhileSubscribed(), replay = 0)

    /** Sub-agent-related changes: the two agents.json files, or the global/project sub-agent directories themselves and anything under them. */
    private fun isAgentChange(change: FileChange): Boolean {
        val path = change.hostPath
        if (path == globalFile().absolutePath) return true
        if (path == projectFile().absolutePath) return true
        val globalAgents = File(containerInstaller.xencodeDir, AGENTS_DIR).absolutePath
        if (path == globalAgents || path.startsWith("$globalAgents/")) return true
        val projectAgents = File(projectXencodeRoot.current(), AGENTS_DIR).absolutePath
        return path == projectAgents || path.startsWith("$projectAgents/")
    }

    /** Currently effective disabled sub-agent names (global + project union, normalized to lowercase). */
    fun disabledNames(): Set<String> =
        (readGlobalDisabled() + readDisabled(projectFile())).map { it.lowercase() }.toSet()

    /** Enables/disables a sub-agent in the config of the given scope. */
    fun setDisabled(name: String, disabled: Boolean, scope: AgentDefinitionScope) {
        val names = (if (scope == AgentDefinitionScope.GLOBAL) readGlobalDisabled() else readDisabled(projectFile()))
            .toMutableSet()
        if (disabled) names.add(name) else names.removeAll { it.equals(name, ignoreCase = true) }
        if (scope == AgentDefinitionScope.GLOBAL) {
            fileAccess.writeFile(GLOBAL_CONFIG_PATH, serializeDisabled(names))
        } else {
            writeDisabled(projectFile(), names)
        }
    }

    private fun readGlobalDisabled(): Set<String> = runCatching {
        if (fileAccess.isFile(GLOBAL_CONFIG_PATH)) parseDisabled(fileAccess.readFile(GLOBAL_CONFIG_PATH))
        else emptySet()
    }.getOrElse {
        FileLogger.w(TAG, "Failed to read $CONFIG_FILE: ${it.message}")
        emptySet()
    }

    companion object {
        private const val TAG = "AgentDefinitionConfigRepository"
        private const val CONFIG_FILE = "agents.json"
        private const val GLOBAL_CONFIG_PATH = "~/.xencode/$CONFIG_FILE"
        private const val XENCODE_DIR = ".xencode"
        private const val AGENTS_DIR = "agents"
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private val PRETTY_JSON = Json { prettyPrint = true }

        internal fun parseDisabled(raw: String): Set<String> {
            val root = runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrElse {
                FileLogger.w(TAG, "Failed to parse sub-agent config JSON: ${it.message}")
                return emptySet()
            }
            return (root["disabled"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toSet()
                ?: emptySet()
        }

        internal fun serializeDisabled(names: Set<String>): String {
            val root = buildJsonObject {
                putJsonArray("disabled") { names.sorted().forEach { add(it) } }
            }
            return PRETTY_JSON.encodeToString(JsonObject.serializer(), root)
        }

        private fun readDisabled(file: File): Set<String> {
            if (!file.isFile) return emptySet()
            return runCatching { parseDisabled(file.readText()) }.getOrElse {
                FileLogger.w(TAG, "Failed to read ${file.name}: ${it.message}")
                emptySet()
            }
        }

        private fun writeDisabled(file: File, names: Set<String>) {
            file.parentFile?.mkdirs()
            val json = serializeDisabled(names)
            // Atomic persist via temp file + rename, so a mid-write crash cannot corrupt the config
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) file.writeText(json)
        }
    }
}
