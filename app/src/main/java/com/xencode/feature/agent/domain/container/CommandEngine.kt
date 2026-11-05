package com.xencode.feature.agent.domain.container

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Events emitted while streaming command execution, rendered line by line in real time by the terminal. */
sealed interface CommandEvent {
    /** One line of standard output/error (stderr is merged into stdout). */
    data class Line(val text: String) : CommandEvent
    /** Command finished, with the exit code (null when even the fallback shell cannot obtain it). */
    data class Exit(val code: Int?) : CommandEvent
}

/**
 * Result of one command execution: bounded full output + exit code (null on timeout/exception).
 * [outputTruncated] being true means the output exceeded the engine cap and was truncated (for the unbounded path see [MAX_UNBOUNDED_CHARS]);
 * callers must not parse such output as a complete result.
 */
data class CommandResult(val output: String, val exitCode: Int?, val outputTruncated: Boolean = false)

/**
 * Abstraction for the command execution backend: decouples "where commands run" from the hard-coded local PRoot.
 *
 * Two implementations:
 * - [LinuxContainerEngine]: local PRoot container, with the original logic unchanged;
 * - `RemoteSshEngine`: remote SSH server, executing via the sshj exec channel.
 *
 * The tool layer ([com.xencode.feature.agent.domain.tool.container.ExecuteCommandTool],
 * [com.xencode.feature.agent.domain.tool.explorer.SearchCodeTool],
 * [com.xencode.feature.git.domain.GitRepository]) depends on this interface rather than concrete implementations,
 * with DI injecting the matching instance per the current execution mode.
 *
 * PRoot-specific methods ([LinuxContainerEngine.startStdioProcess],
 * [LinuxContainerEngine.buildProotInvocation], [LinuxContainerEngine.incPromptInFlight], etc.)
 * do not belong to this interface — they serve only local MCP stdio / credential helpers / terminal PTY, which stay local in remote mode.
 */
interface CommandEngine {

    /** Backend initialization progress, shown live in the UI. In remote mode this usually stays [ContainerInitState.Ready]. */
    val initProgress: StateFlow<ContainerInitState>

    /**
     * Streams command execution: emits a [CommandEvent.Line] for each line read, and a [CommandEvent.Exit] when the command ends.
     * The first call may trigger backend initialization (locally this extracts the rootfs).
     *
     * [timeoutMs] is the maximum command run time in milliseconds; on timeout the command is forcibly terminated and a timeout notice appended.
     */
    fun runCommandStream(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Flow<CommandEvent>

    /**
     * Runs a command synchronously and returns the output. The first call may trigger backend initialization.
     * [timeoutMs] is the maximum command run time in milliseconds; on timeout the command is forcibly terminated and the collected partial output returned.
     */
    suspend fun runCommandSync(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String

    /**
     * Same as [runCommandSync], but also returns the exit code (null on timeout/exception).
     * For callers that need to judge success by the exit code (e.g. git write operations).
     */
    suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): CommandResult

    /**
     * Same as [runCommandSyncWithExit], but the output is **not bounded-truncated** (keeps a continuous head, no omission notice inserted).
     * For callers that need complete text (e.g. the git diff page reading diffs/file contents):
     * the default bounding on the AI tool path ([BoundedOutput], 20k characters of head and tail each) would mix
     * truncation placeholders into the diff stream, making the UI render fake diff lines.
     *
     * Retention stops and [CommandResult.outputTruncated] is set once the output exceeds [MAX_UNBOUNDED_CHARS] characters;
     * callers use that to show an "output too large" hint instead of parsing half the data.
     */
    suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): CommandResult

    /**
     * Runs a command only when the backend is already ready; never triggers initialization (no rootfs extraction / no SSH connection).
     * Returns null when not ready so callers can fall back. Used by read-only tools as a performance enhancement (e.g. search preferring rg).
     */
    suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): CommandResult?

    /** Whether the backend is installed and ready. Locally judged by rootfs/proot; remotely by whether the SSH connection is alive. */
    fun isContainerInstalled(): Boolean

    /** Whether the base packages have been provisioned. Locally judged by the provision flag; remotely always true (tools are the user's responsibility). */
    fun isProvisioned(): Boolean

    /** Default command shell path. Locally bash/sh is chosen by provision state; remotely usually /bin/bash. */
    fun defaultShell(): String

    /**
     * Whether the backend is ready to run commands. null means ready; a non-null value is a hint about why not,
     * shown to the user by the caller to guide them through initialization (locally: "please open the terminal page to initialize first").
     *
     * The default implementation always returns null (remote SSH connection failures are handled by the first command's automatic retry, not this).
     * [LinuxContainerEngine] overrides it to return guidance text based on rootfs/provision state.
     */
    fun notReadyHint(): String? = null

    /**
     * Idempotently ensures the backend is usable: locally extracts rootfs/proot and provisions base packages (slow the first time);
     * remotely establishes the SSH connection. Called only by the terminal page (the sole initialization entry point); command execution entry points no longer trigger it automatically.
     */
    suspend fun ensureInstalled()

    companion object {
        /** Default command timeout in milliseconds. */
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /** Maximum command timeout in milliseconds. */
        const val MAX_TIMEOUT_MS = 1_800_000L
    }
}

/**
 * Sanitizes the command string before logging: commands may carry env prefixes (e.g. the panel script's `AICODE_PROVIDER_API_KEY=...`)
 * or inline credentials (`--header "Authorization: Bearer ..."`). Values of sensitive keys are masked as `***`.
 */
private val SENSITIVE_COMMAND_VALUE_REGEX = Regex(
    """(?i)(?<![A-Za-z0-9])(api[_-]?key|access[_-]?token|auth[_-]?token|token|password|passwd|secret|authorization|auth[_-]?cookie|cookie)\b\s*([=:])\s*(['"]?)(?:Bearer\s+|Basic\s+)?[^\s'"]+"""
)

fun sanitizeCommandForLog(command: String): String =
    SENSITIVE_COMMAND_VALUE_REGEX.replace(command) { m ->
        m.groupValues[1] + m.groupValues[2] + m.groupValues[3] + "***"
    }
