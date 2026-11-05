package com.xencode.feature.workspace.domain.model

/** Workspace type: internal (under the app-private projects root) / external local directory (a device directory chosen by the user) / remote. */
enum class WorkspaceType {
    INTERNAL,
    EXTERNAL_LOCAL,
    REMOTE
}

/**
 * A workspace = the AI's working root directory.
 *
 * - [INTERNAL]: a subfolder under the app-private projects root (filesDir/projects), physically on the private ext4.
 * - [EXTERNAL_LOCAL]: a device-local directory chosen through the system directory picker (e.g. /storage/emulated/0/xxx),
 *   read/written directly both ways, no copying. Deleting the workspace only unlinks it, never deletes the physical files.
 * - [REMOTE]: a subfolder under remoteWorkspacePath on the remote SSH server.
 *
 * The AI's file tools and command execution all root at [path]; switching workspaces switches the AI's scope of operation.
 */
data class Workspace(
    /** Folder name, used as the unique identifier. */
    val name: String,
    /** Absolute path, directly usable with java.io.File and container mounts. */
    val path: String,
    /** Workspace type, defaulting to internal for backward compatibility with old constructors. */
    val type: WorkspaceType = WorkspaceType.INTERNAL,
    /** Whether the workspace is currently available (false when the external local directory was moved/deleted); defaults to available. */
    val available: Boolean = true
)
