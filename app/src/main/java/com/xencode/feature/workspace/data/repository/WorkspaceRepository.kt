package com.xencode.feature.workspace.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xencode.core.datastore.preferencesCorruptionHandler
import com.xencode.R
import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.container.ConnectionState
import com.xencode.feature.agent.domain.container.RemoteSshConnection
import com.xencode.feature.agent.domain.session.SessionUseCase
import com.xencode.feature.settings.data.repository.ExecutionMode
import com.xencode.feature.settings.data.repository.ExecutionModeHolder
import com.xencode.feature.settings.data.repository.GeneralSettingsRepository
import com.xencode.feature.workspace.domain.PathHomeResolver
import com.xencode.feature.workspace.domain.UriPathResolver
import com.xencode.feature.workspace.domain.model.Workspace
import com.xencode.feature.workspace.domain.model.WorkspaceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.workspaceDataStore by preferencesDataStore(
    name = "workspace_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Manages the app's "workspaces/projects".
 *
 * **Local mode**: all projects live under the internal private directory `filesDir/projects/<name>` — a real ext4 path,
 * usable directly by [java.io.File] tooling and by the PRoot container mount, with no runtime storage permission needed, and symlinks are supported.
 *
 * **Remote mode**: a workspace = a subfolder under `remoteWorkspacePath` on the remote SSH server.
 * Listing/creating/deleting operate on the remote directory via SFTP, and [Workspace.path] is the remote absolute path.
 *
 * The currently selected workspace name is persisted in DataStore and survives restarts (local and remote share the same set of names).
 */
@Singleton
class WorkspaceRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val executionModeHolder: ExecutionModeHolder,
    private val remoteSshConnection: RemoteSshConnection,
    private val pathHomeResolver: PathHomeResolver,
    private val sessionUseCase: SessionUseCase,
    private val generalSettingsRepository: GeneralSettingsRepository
) {
    /** Message shown to the UI when validation fails (e.g. directory not writable/unresolvable); set to null once consumed. */
    private val _addError = MutableStateFlow<String?>(null)
    val addError: StateFlow<String?> = _addError.asStateFlow()

    /** Called after the UI consumes the add-failure message, clearing the pending text. */
    fun consumeAddError() {
        _addError.value = null
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val DEFAULT_WORKSPACE = "default"
        private val json = Json { ignoreUnknownKeys = true }

        /** External workspace records -> workspace list; [isDir] decides whether the directory currently exists, determining available. */
        internal fun mapExternalWorkspaces(
            records: List<ExternalWorkspaceRecord>,
            isDir: (String) -> Boolean
        ): List<Workspace> = records.map { rec ->
            Workspace(
                name = rec.name,
                path = rec.path,
                type = WorkspaceType.EXTERNAL_LOCAL,
                available = isDir(rec.path)
            )
        }

        /** Generates a directory name that does not collide with existing workspaces, appending an " (n)" suffix on conflict. */
        internal fun uniqueName(base: String, existing: Set<String>): String {
            if (base !in existing) return base
            var i = 2
            while ("$base ($i)" in existing) i++
            return "$base ($i)"
        }
    }

    private val currentNameKey = stringPreferencesKey("current_workspace_name")
    private val externalWarningDismissedKey = booleanPreferencesKey("external_workspace_warning_dismissed")
    val externalWarningDismissed = context.workspaceDataStore.data
        .map { it[externalWarningDismissedKey] ?: false }

    suspend fun setExternalWarningDismissed(dismissed: Boolean) {
        context.workspaceDataStore.edit { it[externalWarningDismissedKey] = dismissed }
    }

    /** List of external local workspaces (device directories chosen by the user), persisted as JSON and kept across restarts. */
    private val externalWorkspacesKey = stringPreferencesKey("external_workspaces")

    /**
     * Parent directory of all projects; fixed to the internal filesDir (app-private ext4).
     *
     * It must be ext4: the external private directory (getExternalFilesDir) sits on emulated/FUSE storage, where the kernel
     * rejects symlink(); npm/pnpm/yarn/git then fail with `EACCES symlink` when creating symlinks. filesDir is ext4,
     * where symlinks work natively and every toolchain runs with zero config. Outside visibility is provided by the DocumentsProvider, independent of the physical location.
     */
    private val projectsRoot: File by lazy {
        File(context.filesDir, "projects").apply { mkdirs() }
    }

    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    val workspaces: StateFlow<List<Workspace>> = _workspaces.asStateFlow()

    /** Whether removing an external local workspace should also delete its chat history (used by the delete-confirmation message). */
    val deleteExternalWorkspaceSessionsFlow: Flow<Boolean> =
        generalSettingsRepository.deleteExternalWorkspaceSessionsFlow

    private val _current = MutableStateFlow<Workspace?>(null)
    val current: StateFlow<Workspace?> = _current.asStateFlow()

    /** Message shown when remote workspace initialization fails (root path/default workspace creation failed); set to null once consumed by the UI. */
    private val _initError = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = _initError.asStateFlow()

    /** Called after the UI consumes the error message, clearing the pending text. */
    fun consumeInitError() {
        _initError.value = null
    }

    /** Scans and restores the last selected workspace; on first launch in local mode a default workspace is created. Call once at App/ViewModel startup. */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        // Remote mode: wait for the SSH connection to be ready before listing workspaces, avoiding startup race conditions
        if (!isLocal()) {
            waitForConnection()
            ensureRemoteWorkspaceRoot()
        }
        refreshWorkspaces()

        // When no workspace is available, create a default one (same for local/remote) so the AI always has a usable directory;
        // this also covers the case where every workspace is unreachable (e.g. the only external directory was deleted), preventing currentPath from falling back to the parent directory.
        if (_workspaces.value.none { it.available }) {
            val created = if (isLocal()) createLocalFallbackWorkspace() else {
                val fallbackName = uniqueName(DEFAULT_WORKSPACE, _workspaces.value.map { it.name }.toSet())
                createWorkspace(fallbackName)
            }
            refreshWorkspaces()
            if (!isLocal() && created == null && remoteSshConnection.isConnected()) {
                _initError.value = context.getString(R.string.workspace_remote_default_create_failed)
            }
        }

        val savedName = context.workspaceDataStore.data.first()[currentNameKey]
        val saved = savedName?.let { name -> _workspaces.value.firstOrNull { it.name == name } }
        val target = saved?.takeIf { it.available }
            ?: _workspaces.value.firstOrNull { it.available }
        if (saved != null && !saved.available) {
            _initError.value = target?.let {
                context.getString(R.string.workspace_unavailable_fallback, saved.name, it.name)
            } ?: context.getString(R.string.workspace_unavailable_current, saved.name)
            FileLogger.w(TAG, "Last workspace unavailable, falling back: ${saved.name} -> ${target?.name}")
        }
        _current.value = target
        val location = if (isLocal()) projectsRoot.absolutePath else remoteSshConnection.config?.remoteWorkspacePath ?: ""
        FileLogger.i(TAG, "Workspace initialization complete, current: ${target?.name}, root: $location")
        // Remote mode: after selecting a workspace, update the symlink so Bash's ~/workspace points at the current workspace
        if (!isLocal() && target != null) {
            remoteSshConnection.updateWorkspaceSymlink(target.path)
        }
    }

    private fun isLocal(): Boolean =
        executionModeHolder.currentMode() != ExecutionMode.REMOTE_SSH

    /** In remote mode, waits for the SSH connection to become ready (up to 5 seconds), avoiding startup race conditions. */
    /** In remote mode, suspends until the SSH connection is ready (CONNECTED); on connection failure (FAILED) it returns early, keeping the workspace list empty. */
    private suspend fun waitForConnection() {
        val state = remoteSshConnection.connectionState.first {
            it == ConnectionState.CONNECTED || it == ConnectionState.FAILED
        }
        if (state == ConnectionState.FAILED) {
            FileLogger.w(TAG, "SSH connection failed, keeping workspace list empty")
        }
    }

    /** Remote mode: ensures remoteWorkspacePath exists (the user-entered path may not exist yet), creating it with mkdir -p if missing.
     *  @return whether it succeeded; returns false without prompting when the connection is unavailable. */
    private suspend fun ensureRemoteWorkspaceRoot(): Boolean {
        val cfg = remoteSshConnection.config ?: return false
        if (!remoteSshConnection.isConnected()) return false
        val wsRoot = pathHomeResolver.expandHome(cfg.remoteWorkspacePath.trimEnd('/'))
        if (wsRoot.isEmpty()) return false
        val exit = execRemoteExit("mkdir -p ${shellQuote(wsRoot)}")
        if (exit != 0) {
            FileLogger.w(TAG, "Failed to create remote workspace root: $wsRoot (exit=$exit)")
            _initError.value = context.getString(R.string.workspace_remote_root_create_failed)
            return false
        }
        return true
    }

    /** Rescans workspace availability (called when opening the panel or after storage changes); unreachable entries are grayed out and the current workspace falls back if needed. */
    suspend fun refreshAvailability() = withContext(Dispatchers.IO) {
        refreshWorkspaces()
    }

    /** Re-reads the workspace directory list. Locally scans projectsRoot + external local workspaces; remotely runs exec ls remoteWorkspacePath. */
    private suspend fun refreshWorkspaces() {
        _workspaces.value = if (isLocal()) refreshLocalWorkspaces() else refreshRemoteWorkspaces()
        ensureCurrentReachable()
    }

    /**
     * After a refresh, validates the current workspace: if it was removed or is unavailable (directory moved/deleted),
     * fall back to the first available workspace and notify the user via [_initError] when a switch actually happened.
     */
    private suspend fun ensureCurrentReachable() {
        val current = _current.value ?: return
        val matched = _workspaces.value.firstOrNull { it.name == current.name }
        if (matched != null && matched.available) return
        var fallback = _workspaces.value.firstOrNull { it.available }
        if (fallback == null && isLocal()) {
            fallback = createLocalFallbackWorkspace()
        }
        _current.value = fallback
        context.workspaceDataStore.edit { prefs ->
            if (fallback != null) prefs[currentNameKey] = fallback.name else prefs.remove(currentNameKey)
        }
        if (fallback != null && fallback.name != current.name) {
            _initError.value = context.getString(R.string.workspace_unavailable_fallback, current.name, fallback.name)
        }
        FileLogger.w(TAG, "Current workspace unavailable, falling back: ${current.name} -> ${fallback?.name}")
    }

    private suspend fun createLocalFallbackWorkspace(): Workspace? {
        val fallbackName = uniqueName(DEFAULT_WORKSPACE, _workspaces.value.map { it.name }.toSet())
        val fallbackDir = File(projectsRoot, fallbackName)
        if (!fallbackDir.isDirectory && !fallbackDir.mkdirs()) return null
        _workspaces.value = refreshLocalWorkspaces()
        return _workspaces.value.firstOrNull { it.name == fallbackName && it.available }
    }

    private suspend fun refreshLocalWorkspaces(): List<Workspace> {
        val internal = projectsRoot.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?.map { Workspace(name = it.name, path = it.absolutePath) }
            ?: emptyList()
        // External local workspaces: when unreachable (directory moved/deleted), keep the record marked unavailable; it becomes available again automatically once the directory returns
        val external = mapExternalWorkspaces(readExternalWorkspaces()) { File(it).isDirectory }
        return internal + external
    }

    /** Lists subdirectories of remoteWorkspacePath via exec to use as workspaces (SFTP is avoided to dodge an sshj Buffer bug). */
    private suspend fun refreshRemoteWorkspaces(): List<Workspace> {
        val cfg = remoteSshConnection.config ?: run {
            FileLogger.w(TAG, "Failed to list remote workspaces: SSH not configured")
            return emptyList()
        }
        val wsRoot = pathHomeResolver.expandHome(cfg.remoteWorkspacePath.trimEnd('/'))
        return runCatching {
            // ls -d */ lists subdirectories; take the basename
            val output = execRemote("ls -d ${wsRoot}/*/ 2>/dev/null | xargs -n1 basename 2>/dev/null")
            if (output.isBlank()) emptyList()
            else output.lines().filter { it.isNotBlank() }
                .sortedBy { it.lowercase() }
                .map { Workspace(name = it.trim(), path = "$wsRoot/${it.trim()}", type = WorkspaceType.REMOTE) }
        }.getOrElse {
            FileLogger.w(TAG, "Failed to list remote workspaces: $wsRoot", it)
            emptyList()
        }
    }

    /** Runs a remote command synchronously and returns stdout (used for workspace list/create/delete). */
    private suspend fun execRemote(command: String): String =
        withContext(Dispatchers.IO) {
            val session = remoteSshConnection.startExecSession(command)
            try {
                val output = java.io.BufferedReader(java.io.InputStreamReader(session.inputStream)).readText()
                runCatching { session.close() }
                output
            } catch (e: Exception) {
                runCatching { session.close() }
                throw e
            }
        }

    /** Runs a remote command synchronously and returns the exit code. */
    private suspend fun execRemoteExit(command: String): Int =
        withContext(Dispatchers.IO) {
            val session = remoteSshConnection.startExecSession(command)
            try {
                java.io.BufferedReader(java.io.InputStreamReader(session.inputStream)).readText()
                runCatching { session.close() }
                session.exitStatus ?: -1
            } catch (e: Exception) {
                runCatching { session.close() }
                -1
            }
        }

    /** Switches the current workspace and persists it. */
    suspend fun selectWorkspace(name: String) = withContext(Dispatchers.IO) {
        val target = _workspaces.value.firstOrNull { it.name == name && it.available } ?: return@withContext
        _current.value = target
        context.workspaceDataStore.edit { it[currentNameKey] = name }
        FileLogger.i(TAG, "Switched workspace: $name")
        // Remote mode: after switching, update the symlink to point at the new workspace
        if (!isLocal()) {
            remoteSshConnection.updateWorkspaceSymlink(target.path)
        }
    }

    /**
     * Creates a new workspace directory. The name is sanitized into a safe folder name.
     * Local mode runs mkdirs projectsRoot/name; remote mode runs SFTP mkdirs remoteWorkspacePath/name.
     * @return the created [Workspace]; returns null for an illegal name or an existing one.
     */
    suspend fun createWorkspace(rawName: String): Workspace? = withContext(Dispatchers.IO) {
        val name = sanitize(rawName)
        if (name.isEmpty()) {
            FileLogger.w(TAG, "Failed to create workspace: illegal name '$rawName'")
            return@withContext null
        }
        if (isLocal()) {
            val dir = File(projectsRoot, name)
            if (dir.exists() || _workspaces.value.any { it.name == name }) {
                FileLogger.w(TAG, "Failed to create workspace: already exists '$name'")
                return@withContext null
            }
            if (!dir.mkdirs()) {
                FileLogger.e(TAG, "Failed to create workspace: cannot create directory ${dir.absolutePath}")
                return@withContext null
            }
            refreshWorkspaces()
            FileLogger.i(TAG, "Created workspace: $name")
            Workspace(name = name, path = dir.absolutePath)
        } else {
            val cfg = remoteSshConnection.config ?: return@withContext null
            val wsRoot = pathHomeResolver.expandHome(cfg.remoteWorkspacePath.trimEnd('/'))
            val remotePath = "$wsRoot/$name"
            runCatching {
                if (execRemoteExit("test -d ${shellQuote(remotePath)}") == 0) {
                    FileLogger.w(TAG, "Failed to create workspace: already exists '$name'")
                    return@withContext null
                }
                execRemoteExit("mkdir -p ${shellQuote(remotePath)}")
            }.getOrElse {
                FileLogger.e(TAG, "Failed to create remote workspace: $remotePath", it)
                return@withContext null
            }
            refreshWorkspaces()
            FileLogger.i(TAG, "Created workspace (remote): $name")
            Workspace(name = name, path = remotePath, type = WorkspaceType.REMOTE)
        }
    }

    /**
     * Registers a user-chosen device directory as an external local workspace (reads that directory directly both ways, no copying).
     * Validation: resolves to a real path, is a directory, and is writable. Same-name collisions with existing workspaces get an automatic suffix.
     * @return the registered [Workspace]; returns null when validation fails (with the message surfaced via [addError]).
     */
    suspend fun addExternalWorkspace(uri: Uri): Workspace? = withContext(Dispatchers.IO) {
        val uriFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val path = UriPathResolver.toFilePath(context, uri)
        if (path == null) {
            _addError.value = context.getString(R.string.workspace_external_unresolvable)
            FileLogger.w(TAG, "Failed to add local workspace: cannot resolve uri")
            releaseUriGrant(uri, uriFlags)
            return@withContext null
        }
        // Actual reads/writes go through targetSdk 28 legacy storage + java.io.File; the SAF grant merely records the directory-selection permission, it is not the file backend.
        if (path.contains(':')) {
            _addError.value = context.getString(R.string.workspace_external_unsupported_path)
            FileLogger.w(TAG, "Failed to add local workspace: path contains an unsupported colon $path")
            releaseUriGrant(uri, uriFlags)
            return@withContext null
        }
        val dir = File(path)
        val existing = readExternalWorkspaces()
        if (existing.any { it.path == path }) {
            _addError.value = context.getString(R.string.workspace_external_exists)
            FileLogger.w(TAG, "Failed to add local workspace: directory is already a workspace $path")
            return@withContext null
        }
        if (!dir.isDirectory) {
            _addError.value = context.getString(R.string.workspace_external_invalid)
            FileLogger.w(TAG, "Failed to add local workspace: not a directory $path")
            releaseUriGrant(uri, uriFlags)
            return@withContext null
        }
        val writable = runCatching {
            val probe = File.createTempFile(".xencode_write_probe_", ".tmp", dir)
            try {
                probe.writeText("ok")
                if (!probe.delete()) error("probe delete failed")
                true
            } catch (e: Exception) {
                runCatching { probe.delete() }
                throw e
            }
        }.getOrDefault(false)
        if (!writable) {
            _addError.value = context.getString(R.string.workspace_external_unwritable)
            FileLogger.w(TAG, "Failed to add local workspace: directory not writable $path")
            releaseUriGrant(uri, uriFlags)
            return@withContext null
        }
        val allNames = (_workspaces.value.map { it.name } + existing.map { it.name }).toSet()
        val baseName = sanitize(dir.name).ifBlank { DEFAULT_WORKSPACE }
        val name = uniqueName(baseName, allNames)
        writeExternalWorkspaces(existing + ExternalWorkspaceRecord(name, path, uri.toString()))
        refreshWorkspaces()
        val created = _workspaces.value.firstOrNull { it.path == path }
        FileLogger.i(TAG, "Added local workspace: $name ($path)")
        created
    }

    private fun releaseUriGrant(uri: Uri, flags: Int) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
    }

    /** Deletes a workspace. Internal/remote workspaces are deleted along with their files and session records; external local workspaces only have their association removed, the physical directory is not deleted,
     *  and whether their session records are also deleted is decided by the toggle in "Preferences".
     *  If the deleted one is the current workspace, it automatically switches to the first remaining one. */
    suspend fun deleteWorkspace(name: String) = withContext(Dispatchers.IO) {
        val target = _workspaces.value.firstOrNull { it.name == name }
        if (isLocal() && target?.type == WorkspaceType.EXTERNAL_LOCAL) {
            val records = readExternalWorkspaces()
            val removed = records.firstOrNull { it.name == name }
            writeExternalWorkspaces(records.filterNot { it.name == name })
            removed?.let { record ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(record.uri),
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
            }
            if (generalSettingsRepository.deleteExternalWorkspaceSessions()) {
                sessionUseCase.deleteSessionsByWorkspace(target.path)
            }
            FileLogger.i(TAG, "Removed external workspace association: $name")
        } else if (isLocal()) {
            File(projectsRoot, name).deleteRecursively()
            target?.let { sessionUseCase.deleteSessionsByWorkspace(it.path) }
        } else {
            val cfg = remoteSshConnection.config
            if (cfg != null) {
                val remotePath = "${pathHomeResolver.expandHome(cfg.remoteWorkspacePath).trimEnd('/')}/$name"
                runCatching { execRemoteExit("rm -rf ${shellQuote(remotePath)}") }
                    .onFailure { FileLogger.e(TAG, "Failed to delete remote workspace: $remotePath", it) }
            }
            target?.let { sessionUseCase.deleteSessionsByWorkspace(it.path) }
        }
        refreshWorkspaces()
        FileLogger.i(TAG, "Deleted workspace: $name")
    }

    /** Persisted record of an external local workspace. */
    @Serializable
    internal data class ExternalWorkspaceRecord(
        val name: String,
        val path: String,
        val uri: String
    )

    private suspend fun readExternalWorkspaces(): List<ExternalWorkspaceRecord> {
        val raw = context.workspaceDataStore.data.first()[externalWorkspacesKey] ?: return emptyList()
        return runCatching { json.decodeFromString<List<ExternalWorkspaceRecord>>(raw) }
            .getOrElse {
                FileLogger.w(TAG, "Failed to parse external workspace records, ignoring", it)
                emptyList()
            }
    }

    private suspend fun writeExternalWorkspaces(list: List<ExternalWorkspaceRecord>) {
        context.workspaceDataStore.edit { it[externalWorkspacesKey] = json.encodeToString(list) }
    }

    /** Single-quote escaping to keep shell commands safe. */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Path of the current workspace, used as projectRoot / the command working directory.
     * Local mode returns the host workspace absolute path; remote mode returns the selected workspace's remote absolute path (commands cd here).
     * With nothing selected, local falls back to the projects root and remote falls back to the configured remoteWorkspacePath. */
    fun currentPath(): String {
        if (!isLocal()) {
            return _current.value?.path
                ?: remoteSshConnection.config?.remoteWorkspacePath
                    ?.let { pathHomeResolver.expandHome(it) }
                    ?.takeIf { it.isNotBlank() }
                ?: "/"
        }
        return _current.value?.path ?: projectsRoot.absolutePath
    }

    /** Keeps only letters, digits, underscore, hyphen, dot, and space, stripping dangerous characters such as path separators. */
    private fun sanitize(raw: String): String =
        raw.trim()
            .replace(Regex("[^A-Za-z0-9 ._\\u4e00-\\u9fa5-]"), "")
            .trim()
            .take(64)
}
