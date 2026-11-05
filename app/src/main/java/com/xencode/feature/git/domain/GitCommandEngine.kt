package com.xencode.feature.git.domain

import com.xencode.feature.agent.domain.container.CommandEngine
import com.xencode.feature.agent.domain.container.CommandEvent
import com.xencode.feature.agent.domain.container.CommandResult
import com.xencode.feature.agent.domain.container.ContainerInitState
import com.xencode.feature.agent.domain.container.LinuxContainerEngine
import com.xencode.feature.agent.domain.device.DeviceRootEngine
import com.xencode.feature.settings.data.repository.ExecutionMode
import com.xencode.feature.settings.data.repository.ExecutionModeHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Git-module command engine: on the "device ROOT" channel, commands are rerouted to the local PRoot container; others forward unchanged.
 *
 * Background: the root environment of a physical Android device has **no git binary** (neither system nor Magisk
 * ships one), while Debian/Ubuntu container images preinstall it; the Git page, previously going through the
 * [CommandEngine] delegation layer like other tools, saw every `git` become `/system/bin/sh: git: inaccessible or not found`.
 *
 * Only the "device" channel falls back:
 * - REMOTE_SSH: the workspace is on remote Linux; git must run remotely to see the repo - local fallback would be wrong;
 * - LOCAL_PROOT: already a container; behavior unchanged.
 *
 * If the container is not installed either, [LinuxContainerEngine.notReadyHint] returns guidance text the Git page shows.
 */
@Singleton
class GitCommandEngine @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val localEngine: LinuxContainerEngine,
    private val remoteEngine: com.xencode.feature.agent.domain.container.RemoteSshEngine
) : CommandEngine {

    /** Device channel → container; other channels forward as-is (git is not run on the device itself). */
    private fun delegate(): CommandEngine = when (modeHolder.currentMode()) {
        ExecutionMode.REMOTE_SSH -> remoteEngine
        ExecutionMode.DEVICE_ROOT -> localEngine
        ExecutionMode.LOCAL_PROOT -> localEngine
    }

    override val initProgress: StateFlow<ContainerInitState>
        get() = delegate().initProgress

    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = delegate().runCommandStream(command, projectPath, timeoutMs)

    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = delegate().runCommandSync(command, projectPath, timeoutMs)

    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = delegate().runCommandSyncWithExit(command, projectPath, timeoutMs)

    override suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = delegate().runCommandSyncUnbounded(command, projectPath, timeoutMs)

    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? = delegate().runCommandSyncIfReady(command, projectPath, timeoutMs)

    override fun isContainerInstalled(): Boolean = delegate().isContainerInstalled()

    override fun isProvisioned(): Boolean = delegate().isProvisioned()

    override fun defaultShell(): String = delegate().defaultShell()

    override fun notReadyHint(): String? = delegate().notReadyHint()

    override suspend fun ensureInstalled() {
        // On the device channel, initialization targets the container (root itself needs no install), so the Git page can bring the container up too.
        localEngine.ensureInstalled()
    }
}
