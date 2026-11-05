package com.xencode.feature.workspace.domain

import com.xencode.feature.agent.domain.container.ContainerInstaller
import com.xencode.feature.agent.domain.container.RemoteSshConnection
import com.xencode.feature.settings.data.repository.ExecutionMode
import com.xencode.feature.settings.data.repository.ExecutionModeHolder
import com.xencode.feature.workspace.data.repository.WorkspaceRepository
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves where a project-level `.xencode` config directory lives.
 *
 * - Local mode: the config travels with the workspace (`<workspacePath>/.xencode`), directly editable by the AI and git-trackable;
 * - Remote mode: the workspace sits on the SSH server, so [java.io.File] cannot create that directory locally (writes would fail),
 *   and it is stored instead under the app-private directory `filesDir/xencode/projects/<project name-identity hash>/`. The identity is based on
 *   "IP:port:remote path", so same-named paths on different servers never overwrite each other.
 *
 * Uses the same identity algorithm as the project-level memory [com.xencode.feature.agent.domain.memory.ProjectMemorySource].
 */
@Singleton
class ProjectXencodeRoot @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller,
    private val remoteSshConnection: RemoteSshConnection
) {
    /** Project-level config directory of the current workspace. */
    fun current(): File = forPath(workspaceRepository.currentPath())

    /** Project-level config directory for a given workspace path. */
    fun forPath(workspacePath: String): File =
        if (isRemote()) {
            File(File(containerInstaller.xencodeDir, PROJECTS_DIR), projectKey(workspacePath))
        } else {
            File(workspacePath, XENCODE_DIR)
        }

    /** Identity of remote project-level resources under the private directory: project name + the first 8 hex chars of the "IP:port:path" hash. */
    fun projectKey(workspacePath: String): String {
        val name = workspacePath.trimEnd('/').substringAfterLast('/').ifBlank { "default" }
        val cfg = remoteSshConnection.config
        val identity = if (cfg != null) "${cfg.host}:${cfg.port}:$workspacePath" else workspacePath
        val digest = MessageDigest.getInstance("MD5").digest(identity.toByteArray(Charsets.UTF_8))
        val hash = BigInteger(1, digest).toString(16).padStart(32, '0').take(8)
        return "$name-$hash"
    }

    private fun isRemote(): Boolean =
        executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH

    private companion object {
        const val XENCODE_DIR = ".xencode"
        const val PROJECTS_DIR = "projects"
    }
}
