package com.xencode.feature.workspace.domain

import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.container.ContainerInstaller
import com.xencode.feature.agent.domain.container.ContainerProfile
import com.xencode.feature.settings.data.repository.ContainerSettingsRepository
import com.xencode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts between "in-container paths" and "real host paths", so the AI only sees / only uses container paths.
 *
 * Background: the container runs under PRoot with the current profile's rootfs directory as root (`-r rootfs`),
 * and the current workspace directory is bind-mounted as [CONTAINER_ROOT] inside the container (`~/workspace`, expanding to `$HOME/workspace`). Hence there are two deterministic mappings from "in-container paths" to "real host files":
 * - `~/workspace[/…]` -> the host workspace directory (writing it writes the host, and it is visible inside the container — bind mount);
 * - other container absolute paths `/etc/…`, `/root/…` -> the corresponding files under the current profile's rootfs directory (exactly the same files the terminal sees inside the container).
 *
 * This way file tools (read/write/edit) can read/write the whole container filesystem without entering PRoot, consistent with what `execute_command` sees.
 *
 * **profile-aware**: the rootfs directory changes with the currently selected profile (built-in Alpine uses filesDir/rootfs, custom ones use filesDir/rootfs_<id>).
 * [currentProfile] is cached from [ContainerSettingsRepository] to avoid synchronous DataStore reads; the first frame at startup is built-in Alpine, same as before this change.
 *
 * One mapper to unify:
 * - tool inputs (AI-given paths) land on real host files via [toHostFile];
 * - paths echoed/returned by tools are restored to the container view via [toContainerPath] (`~/workspace/…` or `/etc/…`), exposing only container paths to the AI.
 */
@Singleton
class WorkspacePathMapper @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val containerInstaller: ContainerInstaller,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val pathHomeResolver: PathHomeResolver
) {
    companion object {
        /** Workspace root path as the AI sees it. The `~` form keeps prompts/tool descriptions natural; internally matched after expansion via [resolvedContainerRoot]. */
        const val CONTAINER_ROOT = "~/workspace"
        /** In-container path of the AI config directory, bound to the host [ContainerInstaller.xencodeDir] (independent of the rootfs). */
        const val XENCODE_ROOT = "/root/.xencode"
        private const val TAG = "WorkspacePathMapper"
    }

    /**
     * Currently selected profile (cached to avoid synchronous DataStore reads). The first frame at startup is built-in Alpine, same as before this change.
     * Updated by a flow collector after profile switches; during the switch it may briefly disagree with the engine cache, but there is a natural
     * ordering between ensureInstalled and file tool calls, so it does not matter in practice.
     */
    @Volatile
    private var currentProfile: ContainerProfile = ContainerProfile.BUILTIN_ALPINE

    init {
        CoroutineScope(Dispatchers.IO).launch {
            containerSettingsRepository.activeProfileIdFlow.collect { id ->
                currentProfile = resolveProfile(id)
            }
        }
    }

    private suspend fun resolveProfile(id: String): ContainerProfile {
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id }
            ?: profiles.firstOrNull()
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    /** Root directory of the current workspace on the host. */
    private fun hostRoot(): File = File(workspaceRepository.currentPath())

    /** Absolute path of [CONTAINER_ROOT] after expansion (`$HOME/workspace`), used for path matching. Falls back to `/root` while home is not ready. */
    private fun resolvedContainerRoot(): String =
        pathHomeResolver.home().trimEnd('/') + "/workspace"

    /** Root directory of the container rootfs on the host (in-container `/` is this directory; changes with the current profile). */
    private fun rootfsRoot(): File = containerInstaller.rootfsDirFor(currentProfile)

    /** Root of the AI config directory on the host (in-container `/root/.xencode` is this directory; independent of the rootfs). */
    private fun xencodeRoot(): File = containerInstaller.xencodeDir

    /**
     * Resolves an AI-provided path into a real host file. Accepted forms:
     * - container absolute path `~/workspace[/…]` (or the expanded `$HOME/workspace[/…]`) -> mapped to the host workspace;
     * - container absolute path `/root/.xencode[/…]` -> mapped to the host AI config directory (skill / mcp.json, independent of the rootfs);
     * - other container absolute paths `/etc/…`, `/root/…` -> mapped to the corresponding files inside the rootfs (container system files);
     * - relative path `src/Main.kt` -> anchored under the host workspace root;
     *
     * `/root/.xencode` must be matched before the generic `/`->rootfs rule, otherwise it lands in the throwaway copy inside the rootfs (lost on upgrade).
     */
    fun toHostFile(path: String): File {
        val root = hostRoot()
        val wsRoot = resolvedContainerRoot()
        val p = pathHomeResolver.expandHome(path.trim())
        val file = when {
            p == wsRoot || p == "$wsRoot/" || p == CONTAINER_ROOT || p == "$CONTAINER_ROOT/" -> root
            p.startsWith("$wsRoot/") -> File(root, p.removePrefix("$wsRoot/"))
            p == XENCODE_ROOT || p == "$XENCODE_ROOT/" -> xencodeRoot()
            p.startsWith("$XENCODE_ROOT/") -> File(xencodeRoot(), p.removePrefix("$XENCODE_ROOT/"))
            else -> mountedHostFile(p)
                ?: if (p.startsWith("/")) File(rootfsRoot(), p.removePrefix("/")) else File(root, p)
        }
        FileLogger.v(TAG, "toHostFile '$path' -> ${file.absolutePath}")
        return file
    }

    /**
     * Restores a host path back to a container path:
     * - inside the workspace -> `~/workspace/…`;
     * - inside the AI config directory -> `/root/.xencode/…`;
     * - inside the rootfs -> the container absolute path with the rootfs prefix stripped (e.g. `/etc/apk/repositories`);
     * - anything else is returned as-is (rarely occurs).
     *
     * The workspace lives in `filesDir/projects`, AI config in `filesDir/xencode`, rootfs in `filesDir/rootfs` — the three never overlap,
     * so the check order is unambiguous.
     */
    fun toContainerPath(hostPath: String): String {
        val rootPath = hostRoot().absolutePath.replace('\\', '/')
        val xencodePath = xencodeRoot().absolutePath.replace('\\', '/')
        val rootfsPath = rootfsRoot().absolutePath.replace('\\', '/')
        val abs = File(hostPath).absolutePath.replace('\\', '/')
        val raw = hostPath.trim().replace('\\', '/')
        val resolvedWs = resolvedContainerRoot().replace('\\', '/')
        return when {
            abs == rootPath -> CONTAINER_ROOT
            abs.startsWith("$rootPath/") -> CONTAINER_ROOT + "/" + abs.removePrefix("$rootPath/")
            // The expanded $HOME/workspace form is also restored to ~/workspace (bind-mount paths may appear in absolute form)
            raw == resolvedWs || abs == resolvedWs -> CONTAINER_ROOT
            raw.startsWith("$resolvedWs/") -> CONTAINER_ROOT + "/" + raw.removePrefix("$resolvedWs/")
            abs.startsWith("$resolvedWs/") -> CONTAINER_ROOT + "/" + abs.removePrefix("$resolvedWs/")
            abs == xencodePath -> XENCODE_ROOT
            abs.startsWith("$xencodePath/") -> XENCODE_ROOT + "/" + abs.removePrefix("$xencodePath/")
            abs == rootfsPath -> "/"
            abs.startsWith("$rootfsPath/") -> "/" + abs.removePrefix("$rootfsPath/")
            else -> mountedContainerPath(abs) ?: hostPath
        }
    }

    /**
     * Parses the current profile's extra mounts ([ContainerProfile.extraBindings], format `local source:container target`) into
     * (host source, expanded trailing-slash-stripped container target), sorted by container-target length descending (longest prefix first, avoiding nested-mount ambiguity).
     * File tools do not enter PRoot; without this mapping, mounted paths would fall back inside the rootfs,
     * inconsistent with what the in-container shell sees (proot `-b` bind mounts).
     */
    private fun extraMounts(): List<Pair<String, String>> =
        currentProfile.extraBindings.mapNotNull { binding ->
            val idx = binding.indexOf(':')
            val src = (if (idx >= 0) binding.substring(0, idx) else binding).trim()
            val dstRaw = if (idx >= 0) binding.substring(idx + 1) else binding
            val dst = pathHomeResolver.expandHome(dstRaw.trim()).trimEnd('/')
            if (src.isEmpty() || dst.isEmpty()) null else src to dst
        }.sortedByDescending { it.second.length }

    /** When [p] (an already-expanded container path) falls under some extra mount target, maps it to the host source's real file; null otherwise. */
    private fun mountedHostFile(p: String): File? {
        for ((src, dst) in extraMounts()) {
            when {
                p == dst || p == "$dst/" -> return File(src)
                p.startsWith("$dst/") -> return File(src, p.removePrefix("$dst/"))
            }
        }
        return null
    }

    /** When host [abs] falls under some extra mount source, restores it to the container target path; null otherwise. */
    private fun mountedContainerPath(abs: String): String? {
        for ((src, dst) in extraMounts()) {
            val srcAbs = File(src).absolutePath
            when {
                abs == srcAbs -> return dst
                abs.startsWith("$srcAbs/") -> return "$dst/" + abs.removePrefix("$srcAbs/")
            }
        }
        return null
    }
}
