package com.xencode.feature.backup.domain

import java.io.InputStream
import java.io.OutputStream

/**
 * Backup orchestrator: collects snapshots from data sources and packs them, or unpacks and restores in reverse.
 *
 * Export: collect per [BackupOptions] → stream serialization (metadata.json + each *.jsonl) → tar.gz compression → chunked AES-GCM encryption when the passphrase is non-empty.
 * Import: stream-decrypt first when the passphrase is non-empty → untar the tar.gz → validate schemaVersion → merge into data sources in batches.
 * Fully streamed throughout; peak memory is decoupled from total data size (only the current page batch is held).
 */
interface BackupManager {
    /**
     * Streams a generated backup into [output] (the caller owns opening and closing it).
     * When password is null or empty, no encryption is applied and a plaintext tar.gz is written.
     */
    suspend fun export(password: CharArray?, options: BackupOptions, output: OutputStream)

    /**
     * Exports a single session (unencrypted tar.gz): only that session + its messages + todos; restorable directly via [import].
     * @param output the caller owns opening and closing the output stream
     */
    suspend fun exportSession(sessionId: String, output: OutputStream)

    /**
     * Streams, unpacks, and restores a backup file.
     * @param input the caller owns opening and closing the input stream
     * @param password pass null or empty for an unencrypted backup; an encrypted file requires the correct passphrase.
     * @param selectedWorkspaces when non-null, restore only these workspaces' files (selected via [previewImport] before import);
     *   null means a full restore (backup has no workspace data, or a legacy call).
     * @return restore stats (entry counts per data section); a wrong passphrase/format mismatch/too-new version returns failure.
     */
    suspend fun import(
        input: InputStream,
        password: CharArray?,
        selectedWorkspaces: Set<String>? = null
    ): Result<RestoreStats>

    /**
     * Pre-import preview: validates the passphrase/format/version and reads out the backup's workspace list (name + file count).
     * When workspace data exists, the UI should show a selection confirmation (direct-overwrite warning); without data, import needs no dialog.
     * @param input the caller owns opening and closing the input stream
     */
    suspend fun previewImport(input: InputStream, password: CharArray?): Result<ImportPreview>
}

/** Import preview result: the backup's workspace list (for selection). */
data class ImportPreview(
    val workspaces: List<WorkspaceBackupMeta>
) {
    val hasWorkspaceData: Boolean get() = workspaces.isNotEmpty()
}

/** Export scope options; unselected sections stay empty in the snapshot and are skipped on import. */
data class BackupOptions(
    val providers: Boolean = true,
    val remoteConnections: Boolean = true,
    val chatHistory: Boolean = true,
    val mcpServers: Boolean = true,
    val permissionRules: Boolean = true,
    val appSettings: Boolean = true,
    val workspaceFiles: Boolean = false
)

data class RestoreStats(
    val providers: Int = 0,
    val remoteConnections: Int = 0,
    val remoteMounts: Int = 0,
    val chatSessions: Int = 0,
    val agentMessages: Int = 0,
    val todoItems: Int = 0,
    val mcpServers: Int = 0,
    val globalPermissionRules: Int = 0,
    val workspaceFiles: Int = 0
) {
    operator fun plus(other: RestoreStats) = RestoreStats(
        providers = providers + other.providers,
        remoteConnections = remoteConnections + other.remoteConnections,
        remoteMounts = remoteMounts + other.remoteMounts,
        chatSessions = chatSessions + other.chatSessions,
        agentMessages = agentMessages + other.agentMessages,
        todoItems = todoItems + other.todoItems,
        mcpServers = mcpServers + other.mcpServers,
        globalPermissionRules = globalPermissionRules + other.globalPermissionRules,
        workspaceFiles = workspaceFiles + other.workspaceFiles
    )
}
