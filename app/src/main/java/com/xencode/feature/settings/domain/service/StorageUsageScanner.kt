package com.xencode.feature.settings.domain.service

import android.content.Context
import com.xencode.R
import com.xencode.core.util.AILogger
import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.data.local.dao.AgentMessageDao
import com.xencode.feature.agent.data.local.database.AgentDatabase
import com.xencode.feature.agent.domain.container.ContainerInstaller
import com.xencode.feature.agent.domain.container.ContainerProfile
import com.xencode.feature.agent.domain.tool.ToolOutputStore
import com.xencode.feature.agent.domain.tool.file.VisionSessionStore
import com.xencode.feature.network.domain.NetworkHistoryStore
import com.xencode.feature.settings.data.repository.ContainerSettingsRepository
import com.xencode.feature.settings.domain.model.CleanupKind
import com.xencode.feature.settings.domain.model.DeviceSpace
import com.xencode.feature.settings.domain.model.StorageCategory
import com.xencode.feature.settings.domain.model.StorageDetail
import com.xencode.feature.settings.domain.model.StorageDetailKey
import com.xencode.feature.settings.domain.model.StorageEntry
import com.xencode.feature.settings.domain.model.formatStorageSize
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Measures the app's disk usage by component and cleans safely rebuildable temporary data.
 *
 * Categories are mutually exclusive (see [StorageCategory]); their sum is total usage. **External-directory workspaces are excluded**: they physically live in the device's shared
 * storage and may be any large directory the user picked — scanning them in would neither count as app usage nor keep the scan performant.
 */
@Singleton
class StorageUsageScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val toolOutputStore: ToolOutputStore,
    private val visionSessionStore: VisionSessionStore,
    private val networkHistoryStore: NetworkHistoryStore,
    private val agentMessageDao: AgentMessageDao
) {

    /**
     * Emits scan results category by category: instantly available ones like databases and caches come first, rootfs and workspaces last, so the UI can fill in as computing proceeds.
     *
     * Once the collector cancels (e.g. leaving the page), any in-progress directory traversal stops at the next file instead of pointlessly walking the whole tree.
     */
    fun scan(): Flow<StorageEntry> = flow {
        val job = currentCoroutineContext()[Job]
        val cancelled: () -> Boolean = { job?.isActive == false }

        emit(chatEntry())
        emit(cacheEntry(cancelled))
        emit(logsEntry())
        emit(apkEntry())
        emit(checkpointsEntry(cancelled))
        emit(imagesEntry())
        emit(otherDataEntry(cancelled))
        emit(aiConfigEntry(cancelled))
        emit(workspacesEntry(cancelled))
        emit(containerEntry(cancelled))
    }.flowOn(Dispatchers.IO)

    /** Capacity of the storage volume hosting the app's private directory. */
    fun deviceSpace(): DeviceSpace = runCatching {
        DeviceSpace(
            totalBytes = context.filesDir.totalSpace,
            availableBytes = context.filesDir.usableSpace
        )
    }.getOrDefault(DeviceSpace(0, 0))

    /** Performs one cleanup, returning the number of bytes freed. */
    suspend fun clean(kind: CleanupKind): Long = withContext(Dispatchers.IO) {
        when (kind) {
            CleanupKind.Caches -> clearDirContents(context.cacheDir) + clearDirContents(context.codeCacheDir)
            CleanupKind.Logs -> FileLogger.clearLogs() + AILogger.clearLogs()
            CleanupKind.ToolOutput -> clearDirContents(toolOutputStore.outputDir)
            CleanupKind.VisionSessions -> clearDirContents(visionSessionStore.sessionDir)
            CleanupKind.NetworkHistory -> networkHistoryStore.deleteAll()
        }
    }

    // ── Categories ──────────────────────────────────────────────────────────────

    /** Chat history: the entire databases directory (this project has a single Room DB; the WAL -wal/-shm files live there too). */
    private suspend fun chatEntry(): StorageEntry {
        val dbDir = context.getDatabasePath(AgentDatabase.DATABASE_NAME).parentFile
        val bytes = dbDir?.let { dirSize(it) } ?: 0L
        val details = runCatching { agentMessageDao.sessionStorageUsage(TOP_SESSIONS) }
            .getOrDefault(emptyList())
            .filter { it.bytes > 0 }
            .map { usage ->
                StorageDetail(
                    label = usage.title?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.storage_untitled_session),
                    bytes = usage.bytes,
                    note = context.getString(R.string.storage_chat_messages, usage.messageCount)
                )
            }
        return StorageEntry(StorageCategory.Chat, bytes, details)
    }

    /**
     * Containers: `filesDir/rootfs` (built-in) and `filesDir/rootfs_<profileId>` (custom local images).
     *
     * Scanning directories directly instead of the profile list also surfaces leftovers where the profile was deleted but its directory remains; the profile list is used only for display names.
     * Remote SSH profiles have no local rootfs and naturally never appear here.
     */
    private suspend fun containerEntry(cancelled: () -> Boolean): StorageEntry {
        val nameById = runCatching { containerSettingsRepository.customProfilesFlow.first() }
            .getOrDefault(emptyList())
            .associate { it.id to it.name }
        val dirs = context.filesDir.listFiles { f: File ->
            f.isDirectory && (f.name == ROOTFS_DIR || f.name.startsWith("${ROOTFS_DIR}_")) && f.name != IMAGES_DIR
        }?.sortedBy { it.name } ?: emptyList()

        val details = mutableListOf<StorageDetail>()
        var total = 0L
        var largest: Pair<File, Long>? = null
        dirs.forEach { dir ->
            val id = if (dir.name == ROOTFS_DIR) ContainerProfile.BUILTIN_ID else dir.name.removePrefix("${ROOTFS_DIR}_")
            val size = dirSize(dir, cancelled)
            total += size
            val label = nameById[id]
                ?: if (id == ContainerProfile.BUILTIN_ID) ContainerProfile.BUILTIN_ALPINE.name else dir.name
            details += StorageDetail(label = label, bytes = size, note = dir.name)
            if (size > (largest?.second ?: 0L)) largest = dir to size
        }

        // Drill one level into the largest container: what eats gigabytes inside is usually build caches under /usr and /root,
        // invisible in the total alone.
        largest?.let { (dir, _) ->
            dir.listFiles { f: File -> f.isDirectory }
                ?.map { it.name to dirSize(it, cancelled) }
                ?.filter { it.second > 0 }
                ?.sortedByDescending { it.second }
                ?.take(TOP_SUBDIRS)
                ?.forEach { (name, size) ->
                    details += StorageDetail(label = "/$name", bytes = size, indent = true)
                }
        }
        return StorageEntry(StorageCategory.Container, total, details)
    }

    /** Container image packages: downloaded or imported rootfs archives, listed file by file. */
    private fun imagesEntry(): StorageEntry {
        val dir = File(context.filesDir, IMAGES_DIR)
        val files = dir.listFiles { f: File -> f.isFile }?.sortedByDescending { it.length() } ?: emptyList()
        return StorageEntry(
            category = StorageCategory.ContainerImages,
            bytes = files.sumOf { it.length() },
            details = files.map { StorageDetail(label = it.name, bytes = it.length()) }
        )
    }

    /** Workspaces: the app's built-in `filesDir/projects`, listed per project, with chat attachments marked. */
    private fun workspacesEntry(cancelled: () -> Boolean): StorageEntry {
        val root = File(context.filesDir, PROJECTS_DIR)
        val projects = root.listFiles { f: File -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
        val details = projects.map { project ->
            val attachments = dirSize(File(project, ATTACHMENTS_RELATIVE), cancelled)
            StorageDetail(
                label = project.name,
                bytes = dirSize(project, cancelled),
                note = attachments.takeIf { it > 0 }?.let {
                    context.getString(R.string.storage_workspace_attachments, formatStorageSize(it))
                }
            )
        }
        val looseFiles = root.listFiles { f: File -> f.isFile }?.sumOf { it.length() } ?: 0L
        return StorageEntry(StorageCategory.Workspaces, details.sumOf { it.bytes } + looseFiles, details)
    }

    /** AI config and artifacts: `filesDir/xencode`, split by subdirectory (tool output and vision sessions are cleanable). */
    private fun aiConfigEntry(cancelled: () -> Boolean): StorageEntry {
        val root = containerInstaller.xencodeDir
        val cleanableKeys = mapOf(
            toolOutputStore.outputDir.name to StorageDetailKey.TOOL_OUTPUT,
            visionSessionStore.sessionDir.name to StorageDetailKey.VISION_SESSIONS,
            networkHistoryStore.historyDir.name to StorageDetailKey.NETWORK_HISTORY
        )
        val details = root.listFiles { f: File -> f.isDirectory }
            ?.map {
                StorageDetail(
                    label = it.name,
                    bytes = dirSize(it, cancelled),
                    key = cleanableKeys[it.name]
                )
            }
            ?.filter { it.bytes > 0 }
            ?.sortedByDescending { it.bytes }
            ?: emptyList()
        return StorageEntry(StorageCategory.AiConfig, dirSize(root, cancelled), details)
    }

    private fun checkpointsEntry(cancelled: () -> Boolean): StorageEntry =
        StorageEntry(StorageCategory.Checkpoints, dirSize(File(context.filesDir, CHECKPOINTS_DIR), cancelled))

    /**
     * Logs: ask the two loggers directly for file lists instead of guessing directories — they prefer the external private directory
     * and fall back to the internal one when unavailable; the logger decides the path itself.
     */
    private fun logsEntry(): StorageEntry {
        val appLogs = runCatching { FileLogger.listLogFiles().sumOf { it.length() } }.getOrDefault(0L)
        val aiLogs = runCatching { AILogger.listLogFiles().sumOf { it.length() } }.getOrDefault(0L)
        val details = buildList {
            if (appLogs > 0) add(StorageDetail(context.getString(R.string.storage_logs_app), appLogs))
            if (aiLogs > 0) add(StorageDetail(context.getString(R.string.storage_logs_ai), aiLogs))
        }
        return StorageEntry(StorageCategory.Logs, appLogs + aiLogs, details)
    }

    private fun cacheEntry(cancelled: () -> Boolean): StorageEntry =
        StorageEntry(
            category = StorageCategory.Caches,
            bytes = dirSize(context.cacheDir, cancelled) + dirSize(context.codeCacheDir, cancelled)
        )

    /** Other data: the parts of filesDir not covered above (DataStore, background images, fonts, SSH private keys, etc.) plus shared_prefs. */
    private fun otherDataEntry(cancelled: () -> Boolean): StorageEntry {
        val rest = context.filesDir.listFiles()?.filterNot { file ->
            file.name in CLASSIFIED_DIRS || file.name == ROOTFS_DIR || file.name.startsWith("${ROOTFS_DIR}_")
        }?.sumOf { dirSize(it, cancelled) } ?: 0L
        val prefs = context.filesDir.parentFile?.let { dirSize(File(it, PREFS_DIR), cancelled) } ?: 0L
        return StorageEntry(StorageCategory.OtherData, rest + prefs)
    }

    /** The app installation package itself (base APK and splits). */
    private fun apkEntry(): StorageEntry {
        val info = context.applicationInfo
        val paths = (listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList()))
            .filterNotNull()
            .distinct()
        return StorageEntry(StorageCategory.Apk, paths.sumOf { runCatching { File(it).length() }.getOrDefault(0L) })
    }

    // ── Utilities ────────────────────────────────────────────────────────────────

    /** Deletes everything under a directory but keeps the directory itself, returning the bytes freed. */
    private fun clearDirContents(dir: File): Long {
        if (!dir.isDirectory) return 0L
        var freed = 0L
        dir.listFiles()?.forEach { child ->
            val size = dirSize(child)
            if (runCatching { child.deleteRecursively() }.getOrDefault(false)) freed += size
        }
        return freed
    }

    companion object {
        private const val TAG = "StorageUsageScanner"

        private const val ROOTFS_DIR = "rootfs"
        private const val IMAGES_DIR = "rootfs_images"
        private const val PROJECTS_DIR = "projects"
        private const val CHECKPOINTS_DIR = "checkpoints"
        private const val XENCODE_DIR = "xencode"
        private const val PREFS_DIR = "shared_prefs"
        private const val ATTACHMENTS_RELATIVE = ".xencode/attachments"

        /** filesDir entries already covered by other categories, excluded from "Other data". */
        private val CLASSIFIED_DIRS = setOf(
            PROJECTS_DIR, XENCODE_DIR, IMAGES_DIR, CHECKPOINTS_DIR, "logs", "ai-logs"
        )

        private const val TOP_SESSIONS = 5
        private const val TOP_SUBDIRS = 5

        /**
         * Sums a directory's disk usage.
         *
         * Uses [Files.walkFileTree] (which does not follow symlinks by default): rootfs and node_modules are full of symlinks,
         * and following them would double-count or even loop. Failures reading a single entry are skipped so the whole scan never fails.
         */
        internal fun dirSize(target: File, cancelled: () -> Boolean = { false }): Long {
            if (!target.exists()) return 0L
            val path = target.toPath()
            if (runCatching { Files.isSymbolicLink(path) }.getOrDefault(false)) return 0L
            if (target.isFile) return target.length()
            var total = 0L
            runCatching {
                Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) total += attrs.size()
                        return if (cancelled()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult =
                        if (cancelled()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                })
            }.onFailure { FileLogger.w(TAG, "Failed to measure directory usage: ${target.absolutePath}", it) }
            return total
        }
    }
}
