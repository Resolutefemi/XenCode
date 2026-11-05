package com.xencode.feature.agent.domain.permission

import android.content.Context
import com.xencode.core.util.FileLogger
import com.xencode.core.watch.FileChangeBatch
import com.xencode.core.watch.FileChangeHub
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.xencode.feature.workspace.data.repository.WorkspaceRepository
import com.xencode.feature.workspace.domain.ProjectXencodeRoot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for tool authorization rules. Global rules live in the app-private directory (`filesDir/xencode/permissions.json`),
 * project rules in the workspace directory (`.xencode/permissions.json`) so teams can share them via git.
 *
 * The file format is the compact `Tool(pattern)` style, e.g.:
 * ```json
 * {
 *   "permissions": {
 *     "allow": ["Bash(git pull)", "writeFile"],
 *     "deny": ["Bash(rm -rf /)"]
 *   }
 * }
 * ```
 *
 * Security notes: global rules sit in the app-private directory where the AI cannot tamper with them; project rules live inside the workspace
 * and are AI-editable, which is intentional for a project-level declarative config (git-trackable/rollback-able).
 *
 * Concurrency follows [McpConfigRepository]: Mutex-guarded file IO + MutableStateFlow cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PermissionRulesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
    private val projectXencodeRoot: ProjectXencodeRoot,
    private val fileChangeHub: FileChangeHub
) {
    private companion object {
        const val TAG = "PermissionRules"
        const val PERMISSIONS_FILE = "permissions.json"
        /** Project-level config directory name, i.e. `~/workspace/.xencode` inside the container. */
        const val XENCODE_DIR_NAME = ".xencode"
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }

    /** Global permission file: `filesDir/xencode/permissions.json`, next to mcp.json. */
    private val globalFile: File
        get() = File(File(context.filesDir, "xencode"), PERMISSIONS_FILE)

    /** Project-level permission file of the current workspace: `workspacePath/.xencode/permissions.json`. */
    private fun projectFileForPath(workspacePath: String): File =
        File(projectXencodeRoot.forPath(workspacePath), PERMISSIONS_FILE)

    // ── In-memory cache and reactive flows ──────────────────────────────────────

    private val globalState = MutableStateFlow<List<PermissionRule>?>(null)
    private val projectStates = ConcurrentHashMap<String, MutableStateFlow<List<PermissionRule>?>>()
    private val mutex = Mutex()

    // ── External change watching: refresh the cache after permissions.json is edited directly (in-container or by hand), so UI and evaluation take effect immediately ──

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Start watching the config files: after external edits, refresh the cache from disk. Called once at app startup (idempotent).
     * Subscription-driven: [FileChangeHub] watches both permission files' directories and reports merged batches — no homegrown polling.
     * The subscription stays resident because AI permission evaluation must always read the latest rules (unlike a pure UI list that can pause refreshing).
     */
    fun startWatching() {
        watchScope.launch {
            merge(
                fileChangeHub.watchXencode(),
                fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$XENCODE_DIR_NAME")
            ).collect { batch -> refreshFromDisk(batch) }
        }
    }

    /** Handle only hit files; update the cache only when content actually changed (a touch doesn't count). */
    private suspend fun refreshFromDisk(batch: FileChangeBatch) {
        val globalPath = globalFile.absolutePath
        if (batch.changes.any { it.hostPath == globalPath }) {
            val rules = loadFromFile(globalFile)
            if (rules != (globalState.value ?: emptyList<PermissionRule>())) {
                globalState.value = rules
                FileLogger.i(TAG, "Global permission config changed; refreshed")
            }
        }
        val path = workspaceRepository.currentPath()
        if (batch.changes.any { it.hostPath == projectFileForPath(path).absolutePath }) {
            val state = getProjectState(path)
            // Skip while the cache is still unloaded: the first load is done by [ensureProjectLoaded]; a workspace switch is not an external change.
            if (state.value != null) {
                val rules = loadFromFile(projectFileForPath(path))
                if (rules != state.value) {
                    state.value = rules
                    FileLogger.i(TAG, "Project permission config changed; refreshed")
                }
            }
        }
    }

    private fun getProjectState(workspacePath: String): MutableStateFlow<List<PermissionRule>?> =
        projectStates.getOrPut(workspacePath) { MutableStateFlow(null) }

    // ── Lazy loading ──────────────────────────────────────────────────

    private suspend fun ensureGlobalLoaded() {
        if (globalState.value != null) return
        mutex.withLock {
            if (globalState.value != null) return
            globalState.value = loadFromFile(globalFile)
        }
    }

    private suspend fun ensureProjectLoaded(workspacePath: String) {
        val state = getProjectState(workspacePath)
        if (state.value != null) return
        mutex.withLock {
            if (state.value != null) return
            state.value = loadFromFile(projectFileForPath(workspacePath))
        }
    }

    private suspend fun loadFromFile(file: File): List<PermissionRule> =
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext emptyList()
            runCatching {
                JSON.decodeFromString<PermissionFile>(file.readText()).toRuleList()
            }.getOrElse {
                FileLogger.w(TAG, "Failed to read ${file.path}: ${it.message}")
                emptyList()
            }
        }

    private fun writeToFile(file: File, rules: List<PermissionRule>) {
        file.parentFile?.mkdirs()
        file.writeText(JSON.encodeToString(PermissionFile.serializer(), rules.toPermissionFile()))
    }

    // ── Public API ────────────────────────────────────────────────

    /** Name of the currently selected project; null when none is selected (project rules are then unavailable; only global rules apply). */
    fun currentProjectName(): String? = workspaceRepository.current.value?.name

    /** Flow of the current project name, auto-updating on workspace switch, for the UI to collect. */
    val currentProjectNameFlow: Flow<String?> = workspaceRepository.current.map { it?.name }

    /**
     * Current project rules flow: reloads the right project rules automatically as [workspaceRepository.current] switches;
     * emits an empty list when no workspace is selected. For the UI to collect — avoids one-shot snapshots reading null before init completes.
     */
    val currentProjectRulesFlow: Flow<List<PermissionRule>> =
        workspaceRepository.current.flatMapLatest { ws ->
            if (ws == null) flowOf(emptyList()) else projectRulesFlow(ws.name)
        }

    /** Global rules flow for the management UI to observe. */
    val globalRulesFlow: Flow<List<PermissionRule>> = flow {
        ensureGlobalLoaded()
        emitAll(globalState.filterNotNull())
    }

    /** Read all global rules once (for backup). */
    suspend fun getGlobalRulesOnce(): List<PermissionRule> {
        ensureGlobalLoaded()
        return globalState.value ?: emptyList()
    }

    /** Replace the global rules wholesale (for backup import); writes the file atomically and updates the cache. */
    suspend fun setGlobalRules(rules: List<PermissionRule>) {
        mutex.withLock {
            withContext(Dispatchers.IO) { writeToFile(globalFile, rules) }
            globalState.value = rules
        }
    }

    /** Rules flow of the given project, for the management UI to observe. */
    fun projectRulesFlow(projectName: String): Flow<List<PermissionRule>> {
        val workspacePath = workspaceRepository.currentPath()
        val state = getProjectState(workspacePath)
        return flow {
            ensureProjectLoaded(workspacePath)
            emitAll(state.filterNotNull())
        }
    }

    /**
     * For evaluation: current project rules + global rules merged (project first). One-shot snapshot read.
     */
    suspend fun loadEffectiveForCurrentProject(): List<PermissionRule> {
        ensureGlobalLoaded()
        val global = globalState.value ?: emptyList()
        val workspacePath = workspaceRepository.currentPath()
        ensureProjectLoaded(workspacePath)
        val project = getProjectState(workspacePath).value ?: emptyList()
        return project + global
    }

    /** Add a rule by scope. PROJECT writes to the current project; ignored with a warning when there is none. */
    suspend fun add(scope: PermissionScope, rule: PermissionRule) {
        when (scope) {
            PermissionScope.GLOBAL -> editGlobal { if (rule !in it) it.add(rule) }
            PermissionScope.PROJECT -> {
                val workspacePath = workspaceRepository.currentPath()
                editProject(workspacePath) { if (rule !in it) it.add(rule) }
            }
        }
        FileLogger.i(TAG, "Remembered authorization rule[$scope]: ${rule.toolName} ${rule.pattern}")
    }

    suspend fun removeGlobalRule(rule: PermissionRule) = editGlobal { it.remove(rule) }

    suspend fun removeProjectRule(projectName: String, rule: PermissionRule) {
        val workspacePath = workspaceRepository.currentPath()
        editProject(workspacePath) { it.remove(rule) }
    }

    /** Promote a project rule to global: remove from the project, add to global. */
    suspend fun promoteToGlobal(projectName: String, rule: PermissionRule) {
        val workspacePath = workspaceRepository.currentPath()
        editProject(workspacePath) { it.remove(rule) }
        editGlobal { if (rule !in it) it.add(rule) }
        FileLogger.i(TAG, "Promoted to global: ${rule.toolName} ${rule.pattern}")
    }

    // ── Internal writes ────────────────────────────────────────────────

    private suspend fun editGlobal(mutate: (MutableList<PermissionRule>) -> Unit) {
        ensureGlobalLoaded()
        mutex.withLock {
            val list = (globalState.value ?: emptyList()).toMutableList()
            mutate(list)
            withContext(Dispatchers.IO) { writeToFile(globalFile, list) }
            globalState.value = list
        }
    }

    private suspend fun editProject(workspacePath: String, mutate: (MutableList<PermissionRule>) -> Unit) {
        ensureProjectLoaded(workspacePath)
        mutex.withLock {
            val state = getProjectState(workspacePath)
            val list = (state.value ?: emptyList()).toMutableList()
            mutate(list)
            withContext(Dispatchers.IO) { writeToFile(projectFileForPath(workspacePath), list) }
            state.value = list
        }
    }
}
