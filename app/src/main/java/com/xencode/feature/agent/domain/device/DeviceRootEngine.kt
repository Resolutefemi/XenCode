package com.xencode.feature.agent.domain.device

import android.content.Context
import android.system.Os
import com.xencode.R
import com.xencode.core.util.BoundedLineReader
import com.xencode.core.util.FileLogger
import com.xencode.core.util.LINE_TRUNCATED_NOTE
import com.xencode.feature.agent.domain.container.BoundedOutput
import com.xencode.feature.agent.domain.container.CommandEngine
import com.xencode.feature.agent.domain.container.CommandEvent
import com.xencode.feature.agent.domain.container.CommandResult
import com.xencode.feature.agent.domain.container.ContainerInitState
import com.xencode.feature.agent.domain.container.ContainerInstaller
import com.xencode.feature.agent.domain.container.MAX_UNBOUNDED_CHARS
import com.xencode.feature.agent.domain.container.sanitizeCommandForLog
import com.xencode.feature.workspace.data.repository.WorkspaceRepository
import com.xencode.feature.workspace.domain.PathHomeResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-native ROOT command execution backend: the AI's commands skip the PRoot sandbox container and run directly as root on the device.
 *
 * Implementation (user-decided): every command runs via `su -c` (`ProcessBuilder(listOf("su", "-c", command))`,
 * argv passed directly, no shell escaping needed). Magisk/KernelSU shows a grant dialog on the first run; once the user ticks "Remember", later commands run without prompts.
 *
 * Differences from [com.xencode.feature.agent.domain.container.LinuxContainerEngine]:
 * - No rootfs/proot install concept — readiness check = one `su` probe (`id -u` returns 0);
 * - [projectPath] is an absolute device path, entered via `cd '<path>' && <command>`;
 * - The child process gets su's default root environment (PATH usually includes /system/bin:/system/xbin:/sbin, depending on the su implementation).
 *
 * Security note: commands in this channel run as uid 0; the authorization tier ([com.xencode.feature.settings.data.repository.CommandAuthLevel])
 * and the catastrophic-rm guard still apply; the UI additionally requires a countdown confirmation when switching to this channel.
 */
@Singleton
class DeviceRootEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pathHomeResolver: PathHomeResolver,
    private val workspaceRepository: WorkspaceRepository,
    private val containerInstaller: ContainerInstaller
) : CommandEngine {

    // Not private: similar implementations (terminal/file access) reuse [shellQuote] and the timeout constants.
    companion object {
        private const val TAG = "DeviceRootEngine"

        /** Wire the child process's stdin to the null device: keeps "waits-for-input" programs from blocking until timeout (same as LinuxContainerEngine). */
        private val STDIN_FROM_DEV_NULL: ProcessBuilder.Redirect =
            ProcessBuilder.Redirect.from(java.io.File("/dev/null"))

        /** Command timeout ceiling (ms), aligned with [CommandEngine.MAX_TIMEOUT_MS]. */
        private const val MAX_TIMEOUT_MS = CommandEngine.MAX_TIMEOUT_MS

        /** Grace period (ms) for the process to exit after a timeout; force-killed afterwards. */
        private const val TIMEOUT_KILL_GRACE_MS = 200L

        /** How long (ms) the su probe result is cached: avoids forking a probe process for every command. */
        private const val PROBE_CACHE_MS = 30_000L

        /** Single-quote a shell string (`'` → `'\''`). */
        fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }

    /** The device-native channel needs no init: progress is always Ready. */
    private val _initProgress = MutableStateFlow<ContainerInitState>(ContainerInitState.Ready)
    override val initProgress: StateFlow<ContainerInitState> = _initProgress.asStateFlow()

    /** su probe cache: result + probe timestamp. */
    @Volatile
    private var probeCache: Pair<Boolean, Long>? = null

    /**
     * Probe whether ROOT is available: `su -c id -u` exits 0 and the first output line is "0".
     * The result is cached for [PROBE_CACHE_MS] ms; the first probe may trigger the su grant dialog (depends on the su implementation).
     */
    private fun probeRoot(): Boolean {
        val now = System.currentTimeMillis()
        probeCache?.let { (ok, ts) -> if (now - ts < PROBE_CACHE_MS) return ok }
        val ok = runCatching {
            val process = ProcessBuilder(listOf("su", "-c", "id -u"))
                .redirectErrorStream(true)
                .redirectInput(STDIN_FROM_DEV_NULL)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            exitCode == 0 && output.lineSequence().firstOrNull()?.trim() == "0"
        }.getOrElse {
            FileLogger.w(TAG, "su probe failed: ${it.message}")
            false
        }
        probeCache = ok to now
        return ok
    }

    /** Force a fresh probe (used by the "Check ROOT" button in Settings, bypassing the cache). */
    fun reprobeRoot(): Boolean {
        probeCache = null
        return probeRoot()
    }

    override fun isContainerInstalled(): Boolean = probeRoot()

    override fun isProvisioned(): Boolean = probeRoot()

    override fun defaultShell(): String = "/system/bin/sh"

    override fun notReadyHint(): String? =
        if (probeRoot()) null else context.getString(R.string.device_root_not_ready_hint)

    override suspend fun ensureInstalled() {
        if (!probeRoot()) {
            throw IllegalStateException(context.getString(R.string.device_root_not_ready_hint))
        }
    }

    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        notReadyHint()?.let {
            emit(CommandEvent.Line(it))
            emit(CommandEvent.Exit(null))
            return@flow
        }
        emitAll(streamExec(command, projectPath, timeoutMs))
    }.flowOn(Dispatchers.IO)

    /**
     * Streaming execution (same shape as [com.xencode.feature.agent.domain.container.LinuxContainerEngine]):
     * emits [CommandEvent.Line] per line and a final [CommandEvent.Exit]; on timeout the watchdog destroys the process to
     * unblock readLine, then appends a timeout notice line and Exit(null).
     */
    private fun streamExec(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        val effectiveTimeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "Executing command (device, streaming) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
        val process = startRootProcess(command, projectPath)
        val timedOut = AtomicBoolean(false)
        val watchScope = CoroutineScope(Dispatchers.IO + Job())
        val watchdog = launchKillWatchdog(watchScope, process, effectiveTimeout, timedOut, command)
        val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException && process.isAlive) {
                FileLogger.i(TAG, "Command canceled; terminating process: ${sanitizeCommandForLog(command)}")
                runCatching { process.destroy() }
                runCatching { process.destroyForcibly() }
            }
        }
        val reader = BoundedLineReader(InputStreamReader(process.inputStream))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                emit(CommandEvent.Line(if (line.truncated) "${line.text}\n$LINE_TRUNCATED_NOTE" else line.text))
            }
            val exitCode = process.waitFor()
            watchdog.cancel()
            if (timedOut.get()) {
                FileLogger.w(TAG, "Command timed out (${effectiveTimeout}ms) and was terminated: ${sanitizeCommandForLog(command)}")
                emit(CommandEvent.Line(timeoutNotice(effectiveTimeout)))
                emit(CommandEvent.Exit(null))
            } else {
                if (exitCode != 0) FileLogger.w(TAG, "Command exit code=$exitCode: ${sanitizeCommandForLog(command)}")
                emit(CommandEvent.Exit(exitCode))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Same reasoning as the container engine: the watchdog's destroy closes the pipe and makes readLine throw IOException; branch on timeout vs. error
            // so Exit is always emitted and output produced so far is not lost.
            watchdog.cancel()
            if (timedOut.get()) {
                emit(CommandEvent.Line(timeoutNotice(effectiveTimeout)))
                emit(CommandEvent.Exit(null))
            } else {
                FileLogger.e(TAG, "Error reading command output (earlier output preserved): ${sanitizeCommandForLog(command)}", e)
                emit(CommandEvent.Line("[Command execution error: ${e.message}]"))
                emit(CommandEvent.Exit(null))
            }
        } finally {
            cancellationHook?.dispose()
            watchdog.cancel()
            watchScope.cancel()
            runCatching { reader.close() }
            runCatching { process.destroy() }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = withContext(Dispatchers.IO) {
        notReadyHint()?.let { return@withContext it }
        execCaptured(command, projectPath, timeoutMs).output
    }

    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        notReadyHint()?.let { return@withContext CommandResult(it, null) }
        val r = execCaptured(command, projectPath, timeoutMs)
        CommandResult(r.output, r.exitCode, r.truncated)
    }

    override suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        notReadyHint()?.let { return@withContext CommandResult(it, null) }
        val r = execCaptured(command, projectPath, timeoutMs, unbounded = true)
        CommandResult(r.output, r.exitCode, r.truncated)
    }

    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? {
        if (!probeRoot()) return null
        val r = execCaptured(command, projectPath, timeoutMs)
        return CommandResult(r.output, r.exitCode, r.truncated)
    }

    /** Internal result of one device execution: capped full output + exit code (null on timeout/error). */
    private data class ExecResult(val output: String, val exitCode: Int?, val truncated: Boolean = false)

    /** Execute synchronously and capture output (same shape as the container engine: capped accumulation + watchdog + cancel cleanup). */
    private suspend fun execCaptured(
        command: String,
        projectPath: String?,
        timeoutMs: Long,
        unbounded: Boolean = false
    ): ExecResult = withContext(Dispatchers.IO) {
        try {
            val effectiveTimeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
            FileLogger.d(TAG, "Executing command (device, sync) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
            val process = startRootProcess(command, projectPath)
            val timedOut = AtomicBoolean(false)
            val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException && process.isAlive) {
                    FileLogger.i(TAG, "Command canceled; terminating process: ${sanitizeCommandForLog(command)}")
                    runCatching { process.destroy() }
                    runCatching { process.destroyForcibly() }
                }
            }

            val output = if (unbounded) BoundedOutput.hardCapped(MAX_UNBOUNDED_CHARS) else BoundedOutput()
            var exitCode: Int? = null
            try {
                coroutineScope {
                    val watchdog = launchKillWatchdog(this, process, effectiveTimeout, timedOut, command)
                    val reader = BoundedLineReader(InputStreamReader(process.inputStream))
                    try {
                        while (true) {
                            val line = reader.readLine() ?: break
                            output.append(if (line.truncated) "${line.text}\n$LINE_TRUNCATED_NOTE" else line.text)
                            output.append("\n")
                        }
                        exitCode = process.waitFor()
                    } finally {
                        watchdog.cancel()
                        runCatching { reader.close() }
                    }
                }
            } finally {
                cancellationHook?.dispose()
                runCatching { process.destroy() }
            }

            if (timedOut.get()) {
                FileLogger.w(TAG, "Command timed out (${effectiveTimeout}ms) and was terminated: ${sanitizeCommandForLog(command)}")
                output.append(timeoutNotice(effectiveTimeout))
                output.append("\n")
                ExecResult(output.build(), null, output.truncated)
            } else {
                ExecResult(output.build(), exitCode, output.truncated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Command execution error: ${sanitizeCommandForLog(command)}", e)
            ExecResult("Error: ${e.message}", null)
        }
    }

    /** Watchdog: if the process is still alive at the deadline, mark it timed out and terminate gracefully → forcibly (the device channel has no credential-dialog grace case). */
    private fun launchKillWatchdog(
        scope: CoroutineScope,
        process: Process,
        timeoutMs: Long,
        timedOut: AtomicBoolean,
        command: String
    ): Job = scope.launch {
        delay(timeoutMs)
        if (!process.isAlive) return@launch
        timedOut.set(true)
        FileLogger.w(TAG, "Command exceeded ${timeoutMs}ms; terminating process: ${sanitizeCommandForLog(command)}")
        runCatching { process.destroy() }
        delay(TIMEOUT_KILL_GRACE_MS)
        if (process.isAlive) runCatching { process.destroyForcibly() }
    }

    /** Timeout notice line (appended to the output, fed back to the model / shown to the user). */
    private fun timeoutNotice(timeoutMs: Long): String =
        "[Command timed out after ${timeoutMs}ms and was forcibly terminated]"

    /**
     * Start a command as root: `su -c '<command>'`; `cd` into [projectPath] first when it is non-empty.
     * argv is passed item by item (no shell concatenation); the command text itself is still parsed by the root shell (same semantics as the container engine).
     *
     * The prefix first runs `export HOME=<filesDir>`: su resets HOME to /root (nonexistent on the device),
     * while the prompt conventions use `~/workspace` as the workspace alias — with HOME pointing at filesDir, `~/workspace` inside the shell
     * resolves to the same place as the file tools' path alias (via the symlinks created by [refreshWorkspaceLinks]).
     */
    private fun startRootProcess(command: String, projectPath: String?): Process {
        refreshWorkspaceLinks()
        val sb = StringBuilder()
        sb.append("export HOME=").append(shellQuote(pathHomeResolver.home())).append("; ")
        if (!projectPath.isNullOrBlank()) {
            sb.append("cd ").append(shellQuote(projectPath)).append(" && ")
        }
        sb.append(command)
        val pb = ProcessBuilder(listOf("su", "-c", sb.toString()))
        pb.redirectErrorStream(true)
        pb.redirectInput(STDIN_FROM_DEV_NULL)
        return pb.start()
    }

    /** Workspace path of the last successful linking: skipped when the workspace is unchanged, avoiding repeated stat/symlink per command. */
    @Volatile
    private var linkedWorkspace: String? = null

    /**
     * Create `workspace` / `.xencode` symlinks under `$HOME` (the app-private filesDir), pointing at the current
     * workspace and the global config directory respectively — mirroring the container-mode layout of the `~/workspace` bind mount
     * and the `/root/.xencode` binding, so a manual `cd ~/workspace` or `ls ~/.xencode` in the device root shell matches the file tools' path aliases.
     *
     * Best-effort: the links live inside the ext4 filesDir (the app can create them itself, no su needed), and pointing at external
     * targets like /sdcard is fine too; an existing real directory/file is never overwritten. All failures are silently ignored —
     * the file tools' alias mapping does not depend on the links actually existing.
     */
    fun refreshWorkspaceLinks() {
        val ws = workspaceRepository.currentPath().trim().trimEnd('/')
        if (ws.isEmpty() || ws == linkedWorkspace) return
        val home = context.filesDir.absolutePath
        linkIfPossible(File(home, "workspace"), ws)
        linkIfPossible(File(home, ".xencode"), containerInstaller.xencodeDir.absolutePath)
        linkedWorkspace = ws
    }

    /** Create a single symlink; skipped when [link] already points at the same target, and never overwrites a real file/directory. */
    private fun linkIfPossible(link: File, target: String) {
        runCatching {
            val path = link.toPath()
            if (java.nio.file.Files.isSymbolicLink(path)) {
                val existing = runCatching { Os.readlink(link.absolutePath) }.getOrNull()
                if (existing == target) return
                if (!link.delete()) return
            } else if (link.exists()) {
                return
            }
            Os.symlink(target, link.absolutePath)
        }.onFailure { FileLogger.w(TAG, "Failed to create symlink ${link.absolutePath} -> $target: ${it.message}") }
    }
}
