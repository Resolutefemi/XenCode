package com.xencode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xencode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.executionModeDataStore by preferencesDataStore(
    name = "execution_mode_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** Execution environment mode. */
enum class ExecutionMode {
    /** Local PRoot container (original behavior). */
    LOCAL_PROOT,
    /** Remote SSH server. */
    REMOTE_SSH,
    /** Device ROOT: commands run directly on this device as root via `su` (requires a rooted device). */
    DEVICE_ROOT
}

/**
 * Persists the current execution mode (local PRoot / remote SSH).
 *
 * Remote connection parameters are no longer persisted here; instead
 * [com.xencode.feature.agent.domain.container.ActiveRemoteConnectionResolver] resolves them in real time from the currently active container profile.
 * When the mode switches, the DI layer injects the corresponding [com.xencode.feature.agent.domain.container.CommandEngine]
 * and [com.xencode.feature.workspace.domain.FileAccessProvider] implementations accordingly.
 */
@Singleton
class ExecutionModeRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val MODE_KEY = stringPreferencesKey("execution_mode")
    }

    /** Current execution mode; defaults to local PRoot when unset. */
    val executionModeFlow: Flow<ExecutionMode> = context.executionModeDataStore.data.map { prefs ->
        prefs[MODE_KEY]?.let {
            runCatching { ExecutionMode.valueOf(it) }.getOrNull()
        } ?: ExecutionMode.LOCAL_PROOT
    }

    suspend fun setExecutionMode(mode: ExecutionMode) {
        context.executionModeDataStore.edit { it[MODE_KEY] = mode.name }
    }
}

/**
 * Normalizes the remote workspace root path. The old version hardcoded the default as `/home/<username>/workspace` (wrong for users
 * whose home is not `/home/<username>`, e.g. root's `/root`); `~/workspace` is the path occupied by a symlink
 * and cannot serve as the workspace root (it would collide with the link, forming a self-reference). Both kinds of leftover values, and empty values, are reset to the default.
 */
internal fun normalizeRemoteWorkspacePath(stored: String?, username: String): String {
    val v = stored?.trim().orEmpty()
    return if (v.isEmpty() || v == "/home/$username/workspace" || v == "~/workspace") {
        DEFAULT_REMOTE_WORKSPACE_ROOT
    } else {
        v
    }
}

/**
 * Default value for the remote workspace root directory. **Must not use `~/workspace`** — that is the path occupied by the "current workspace" symlink,
 * and using the same name would make the root directory collide with the link.
 */
internal const val DEFAULT_REMOTE_WORKSPACE_ROOT = "~/.xencode/workspaces"
