package com.xencode.feature.settings.domain.model

import androidx.annotation.StringRes
import com.xencode.R

/**
 * Storage usage categories. **Categories are mutually exclusive**; their sum is the app's total usage; enum order is the UI display order.
 *
 * Category boundaries follow physical directories: directories holding logs and caches are not double-counted in [OtherData],
 * and chat attachments inside workspaces count toward [Workspaces] (shown only as a subtitle hint, not a separate category).
 */
enum class StorageCategory(@param:StringRes val labelRes: Int) {
    Chat(R.string.storage_category_chat),
    Container(R.string.storage_category_container),
    ContainerImages(R.string.storage_category_container_images),
    Workspaces(R.string.storage_category_workspaces),
    AiConfig(R.string.storage_category_ai_config),
    Checkpoints(R.string.storage_category_checkpoints),
    Logs(R.string.storage_category_logs),
    Caches(R.string.storage_category_caches),
    OtherData(R.string.storage_category_other),
    Apk(R.string.storage_category_apk)
}

/**
 * One detail entry under a category (container name, project name, session title, subdirectory name, etc.).
 *
 * @param key Stable identifier for the UI to look up per-item values (e.g. find the cleanable size by `tool-output`); null for display-only items.
 * @param indent Whether to display indented as a drill-down of the detail above.
 */
data class StorageDetail(
    val label: String,
    val bytes: Long,
    val note: String? = null,
    val key: String? = null,
    val indent: Boolean = false
)

data class StorageEntry(
    val category: StorageCategory,
    val bytes: Long,
    val details: List<StorageDetail> = emptyList()
)

/** Possible values of [StorageDetail.key]: decouples "which detail items are cleanable" from their directory names. */
object StorageDetailKey {
    const val TOOL_OUTPUT = "toolOutput"
    const val VISION_SESSIONS = "visionSessions"
    const val NETWORK_HISTORY = "networkHistory"
}

/** Cleanable items: all temporary data that is auto-rebuildable or only affects history review; no user content. */
enum class CleanupKind(
    @param:StringRes val labelRes: Int,
    @param:StringRes val descRes: Int
) {
    Caches(R.string.storage_clean_caches, R.string.storage_clean_caches_desc),
    Logs(R.string.storage_clean_logs, R.string.storage_clean_logs_desc),
    ToolOutput(R.string.storage_clean_tool_output, R.string.storage_clean_tool_output_desc),
    VisionSessions(R.string.storage_clean_vision, R.string.storage_clean_vision_desc),
    NetworkHistory(R.string.storage_clean_network_history, R.string.storage_clean_network_history_desc)
}

/** Capacity info of the storage volume hosting the app's private directory. */
data class DeviceSpace(
    val totalBytes: Long,
    val availableBytes: Long
)

/** Converts bytes to readable text. Always uses [java.util.Locale.US] decimal points, avoiding commas from the system locale. */
internal fun formatStorageSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
