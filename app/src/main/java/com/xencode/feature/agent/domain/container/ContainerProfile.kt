package com.xencode.feature.agent.domain.container

import com.xencode.feature.settings.data.repository.ExecutionMode
import kotlinx.serialization.Serializable

/**
 * A switchable container configuration: image source + shell path + extra proot bindings/arguments.
 *
 * The built-in [BUILTIN_ALPINE] describes the existing Alpine rootfs (from assets). User-defined profiles supply
 * a rootfs by importing a tar.gz. Every container shows the same provisioning menu on first terminal entry (see assets/xencode/provision.sh),
 * where the user picks automatic or manual installation of base tools; a failed install does not block entering the shell — the handling is uniform.
 *
 * Remote SSH profiles ([mode] == [ExecutionMode.REMOTE_SSH]) import no local rootfs; instead they bind an
 * SSH channel already configured in the workspace ([RootfsSource.RemoteSsh]), with command execution going through [RemoteSshEngine].
 */
@Serializable
data class ContainerProfile(
    val id: String,
    val name: String,
    val rootfsSource: RootfsSource,
    /** Shell for custom images (e.g. /bin/sh or /bin/bash); when unset, chosen automatically by provision state. */
    val shellPath: String?,
    /** Extra -b bindings, e.g. ["/sdcard:/mnt/sdcard"], each appended to the proot argv as `-b <binding>`. */
    val extraBindings: List<String> = emptyList(),
    /** Extra proot arguments appended verbatim to the base argv (e.g. ["-k","..."]). */
    val extraArgs: List<String> = emptyList(),
    /** Custom environment variables injected into in-container processes (overriding same-named defaults). */
    val env: Map<String, String> = emptyMap(),
    val isBuiltin: Boolean,
    /** This profile's execution mode: local PRoot container or remote SSH. Selecting it switches the global [ExecutionMode] accordingly. */
    val mode: ExecutionMode = ExecutionMode.LOCAL_PROOT,
    /** Creation time (millisecond timestamp); the container list sorts by it descending; legacy data/built-in default to 0 and sort last. */
    val createdAt: Long = 0
) {
    companion object {
        const val BUILTIN_ID = "builtin-alpine"

        /**
         * proot arguments injected by default for new containers. Deliberately goes through the same path as manually
         * added ones ([extraArgs], appended at the end of the proot argv) instead of hard-coding argv in the engine — a hard-coded position differs from manual additions, which broke
         * --link2symlink behavior and could not be undone on the edit page.
         *  - --link2symlink: Android SELinux forbids the app domain from creating hard links, and dpkg's link()-based
         *    file backups would get Permission denied; proot emulates hard links with symlinks to work around it;
         *  - --kill-on-exit: when proot exits it cascades kills to in-container child processes, avoiding orphan processes left by resetting a container while the terminal still runs, which caused hangs.
         */
        val DEFAULT_PROOT_ARGS = listOf("--link2symlink", "--kill-on-exit")

        /** Built-in Alpine profile: image comes from assets, reusing the existing install/provision flow. */
        val BUILTIN_ALPINE = ContainerProfile(
            id = BUILTIN_ID,
            name = "Built-in Alpine",
            rootfsSource = RootfsSource.Asset("alpine-rootfs.bin"),
            shellPath = null,
            extraArgs = DEFAULT_PROOT_ARGS,
            isBuiltin = true
        )
    }
}

@Serializable
sealed interface RootfsSource {
    /** Built-in: the rootfs file in assets (used by [ContainerProfile.BUILTIN_ALPINE]). */
    @Serializable
    data class Asset(val path: String) : RootfsSource

    /** User-imported tar.gz, referenced via content uri and extracted to filesDir/rootfs_<id>. */
    @Serializable
    data class LocalFile(val uri: String) : RootfsSource

    /** Remote SSH: binds an SSH channel configured in the workspace (connectionId) + a remote workspace path; no local rootfs imported. */
    @Serializable
    data class RemoteSsh(
        val connectionId: String,
        val remoteWorkspacePath: String
    ) : RootfsSource
}
