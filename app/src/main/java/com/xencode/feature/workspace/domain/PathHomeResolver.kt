package com.xencode.feature.workspace.domain

import android.content.Context
import com.xencode.feature.agent.domain.container.RemoteSshConnection
import com.xencode.feature.settings.data.repository.ExecutionMode
import com.xencode.feature.settings.data.repository.ExecutionModeHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the home directory of the current execution environment (local PRoot container / remote SSH server / device ROOT), used by tools to expand `~`.
 *
 * Each environment obtains its real home differently: the local container reads the `$HOME` cache once ready ([containerHome], written by
 * [com.xencode.feature.agent.domain.container.LinuxContainerEngine]); remote reads the remote `$HOME` cache
 * after connecting ([RemoteSshConnection.remoteHome]); device ROOT always uses the app-private filesDir
 * (readable/writable from both the app and root shell channels, and capable of hosting `workspace` / `.xencode` symlinks,
 * so the shell's `~/workspace` and `~/.xencode` match the file tools' path aliases). This class hides the differences and
 * uniformly provides [home] and [expandHome], so no tool hardcodes its own home (e.g. `/root`) or re-checks the execution mode.
 */
@Singleton
class PathHomeResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val executionModeHolder: ExecutionModeHolder,
    private val remoteSshConnection: RemoteSshConnection
) {
    /** Real home inside the local container (reads the $HOME cache once the container is ready); null until ready. */
    @Volatile
    var containerHome: String? = null

    /**
     * The current environment's home: remote uses the remote user's home (cached after connecting), device ROOT uses the app-private filesDir,
     * and the local container uses the container home; falls back to /root when unknown.
     */
    fun home(): String = when (executionModeHolder.currentMode()) {
        ExecutionMode.REMOTE_SSH -> remoteSshConnection.remoteHome ?: "/root"
        ExecutionMode.DEVICE_ROOT -> context.filesDir.absolutePath
        ExecutionMode.LOCAL_PROOT -> containerHome ?: "/root"
    }

    /**
     * AI config root for the current execution environment (`<home>/.xencode`): `/root/.xencode` in the local container,
     * and `.xencode` under the server user's home when remote. One place for code that executes/displays files in it,
     * avoiding a hardcoded `/root` (which points wrongly for non-root remote users).
     */
    fun xencodeRoot(): String = home().trimEnd('/') + "/.xencode"

    /** Expands a `~` or `~/` prefix into the current environment's home path; other paths are returned as-is. */
    fun expandHome(path: String): String {
        val h = home()
        return when {
            path == "~" -> h
            path.startsWith("~/") -> h.trimEnd('/') + path.removePrefix("~")
            else -> path
        }
    }
}
