package com.xencode.feature.agent.domain.container

import com.xencode.feature.agent.domain.device.DeviceRootEngine
import com.xencode.feature.settings.data.repository.ExecutionMode
import com.xencode.feature.settings.data.repository.ExecutionModeHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Delegating layer for [CommandEngine]: holds all three implementations — local container, remote SSH, and
 * device ROOT — at once, forwarding each call to the matching one per [ExecutionModeHolder.currentMode].
 *
 * This removes Hilt injection timing from the equation — no matter when [CommandEngine] is first injected,
 * the current mode is only read when a command actually executes.
 *
 * Beyond the global mode, callers may also pick the execution environment explicitly ([engineFor]): the global mode
 * is a "preference", and the AI can explicitly choose another channel when appropriate (e.g. when it needs the full in-container Linux toolchain).
 */
@Singleton
class DelegatingCommandEngine @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val localEngine: LinuxContainerEngine,
    private val remoteEngine: RemoteSshEngine,
    private val deviceRootEngine: DeviceRootEngine
) : CommandEngine {

    /** Explicit execution environment. [AUTO] follows the global mode preference. */
    enum class TargetEnv { AUTO, CONTAINER, DEVICE, REMOTE }

    private fun delegate(): CommandEngine = when (modeHolder.currentMode()) {
        ExecutionMode.REMOTE_SSH -> remoteEngine
        ExecutionMode.DEVICE_ROOT -> deviceRootEngine
        ExecutionMode.LOCAL_PROOT -> localEngine
    }

    /**
     * Returns the engine for a given execution environment. [TargetEnv.AUTO] equals the current global mode (the preference);
     * the other values bypass the global mode and connect straight to the engine, letting the AI switch channels explicitly per scenario.
     */
    fun engineFor(env: TargetEnv): CommandEngine = when (env) {
        TargetEnv.AUTO -> delegate()
        TargetEnv.CONTAINER -> localEngine
        TargetEnv.DEVICE -> deviceRootEngine
        TargetEnv.REMOTE -> remoteEngine
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

    override suspend fun ensureInstalled() = delegate().ensureInstalled()
}
