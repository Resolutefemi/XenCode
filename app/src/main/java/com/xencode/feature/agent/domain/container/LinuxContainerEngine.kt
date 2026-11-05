package com.xencode.feature.agent.domain.container

import com.xencode.core.util.BoundedLineReader
import com.xencode.core.util.FileLogger
import com.xencode.core.util.LINE_TRUNCATED_NOTE
import com.xencode.R
import com.xencode.feature.settings.data.repository.ExecutionMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local PRoot container command execution backend, implementing [CommandEngine].
 *
 * Original logic unchanged — only added `: CommandEngine` and `override` on the public methods.
 * [CommandEvent] and [CommandResult] have been promoted to top-level types (see [CommandEngine.kt]),
 * no longer defined here. PRoot-specific methods ([startStdioProcess]/[buildProotInvocation]/
 * [incPromptInFlight], etc.) are not part of the interface; they serve local MCP stdio / credential helper / terminal PTY only.
 */
/**
 * A complete description of one PRoot invocation: executable + argument list + environment variables.
 *
 * Element 0 of [argv] is the proot binary path; the rest are arguments.
 *
 * Both consumers need the "full argv":
 *  - [ProcessBuilder] takes the argv list directly;
 *  - Termux TerminalSession's cmd is only used by execvp to locate the executable; the real argv is
 *    formed verbatim from its args parameter (native execvp(cmd, argv), which does not auto-fill argv[0]), so args must
 *    also be the full argv "including argv[0]=proot binary", otherwise every option shifts by one and proot mistakes the
 *    rootfs path for the guest program ("is not a regular file"). [executable] is only used for the former's cmd slot.
 */
data class ProotInvocation(
    val argv: List<String>,
    val env: Map<String, String>
) {
    val executable: String get() = argv.first()

    /**
     * The full environment array ("KEY=VALUE") for Termux [com.termux.terminal.TerminalSession].
     *
     * [ProcessBuilder]'s environment() starts as a copy of the parent (app) process environment, with [env] layered on,
     * so proot gets system variables like ANDROID_ROOT/ANDROID_DATA/LD_LIBRARY_PATH; but
     * TerminalSession receives a "complete, non-inheriting" environment array — feeding it only [env] makes proot,
     * a dynamically linked Android executable, fail exec for lack of system environment and exit instantly (the terminal shows
     * "session ended" with no other error logged). So the parent environment is explicitly merged here, replicating ProcessBuilder semantics.
     */
    val ptyEnvArray: Array<String>
        get() = (System.getenv() + env).map { "${it.key}=${it.value}" }.toTypedArray()
}

@Singleton
class LinuxContainerEngine @Inject constructor(
    @param:ApplicationContext private val context: android.content.Context,
    private val containerInstaller: ContainerInstaller,
    private val containerOsDetector: ContainerOsDetector,
    private val containerSettingsRepository: com.xencode.feature.settings.data.repository.ContainerSettingsRepository,
    private val pathHomeResolver: com.xencode.feature.workspace.domain.PathHomeResolver
) : CommandEngine {
    /** Live progress of container initialization, shared as one state by all entry points (terminal page/AI/background terminal/MCP). */
    private val _initProgress = MutableStateFlow<ContainerInitState>(ContainerInitState.Idle)
    override val initProgress: StateFlow<ContainerInitState> = _initProgress.asStateFlow()

    /** Serializes container initialization (including task creation and execution inside the background initScope), preventing concurrent duplicate extraction/configuration from multiple entry points. */
    private val initMutex = Mutex()

    /**
     * A dedicated coroutine scope for container initialization: not cancelled by any page/caller,
     * so initialization keeps running in the background after leaving the terminal page and can be reused or awaited on next entry.
     */
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The in-flight initialization job (guarded by [initMutex]); set to null when done. */
    private var initJob: Job? = null

    /**
     * The currently selected profile (cached from [containerSettingsRepository.activeProfileIdFlow] to avoid synchronous DataStore reads).
     * The first frame after startup is the built-in Alpine (same as before the change); updated by the flow collector after a profile switch. Falls back to built-in for safety.
     */
    @Volatile
    private var currentProfile: ContainerProfile = ContainerProfile.BUILTIN_ALPINE

    init {
        CoroutineScope(Dispatchers.IO).launch {
            containerSettingsRepository.activeProfileIdFlow.collect { id ->
                currentProfile = resolveProfile(id)
            }
        }
    }

    /**
     * Resolves a profile by id: look it up in the persisted list, fall back to the first list entry, then finally the built-in Alpine (so the engine always works).
     * A persisted Alpine override in the list wins over the [ContainerProfile.BUILTIN_ALPINE] constant (user-edited config takes effect).
     */
    private suspend fun resolveProfile(id: String): ContainerProfile {
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id }
            ?: profiles.firstOrNull()
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    /**
     * Count of credential requests currently in flight (pairs where the custom credential helper is blocked awaiting the app popup).
     *
     * Incremented by [com.xencode.feature.credentials.data.CredentialRequestBridge] on receiving the helper's cred-req and
     * decremented when writing back the cred-resp. [launchKillWatchdog] relaxes the timeout based on it — while a helper is in flight the user's git command is "waiting for credentials",
     * not "hung"; the watchdog must not kill it at the normal timeout. See the notes on that change there.
     */
    private val credentialPromptInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /** A credential request entering flight (called when the bridge receives the helper's cred-req). */
    fun incPromptInFlight() { credentialPromptInFlight.incrementAndGet() }

    /** A credential request leaving flight (called when the bridge writes back the cred-resp). */
    fun decPromptInFlight() { credentialPromptInFlight.decrementAndGet() }

    companion object {
        private const val TAG = "LinuxContainerEngine"

        /**
         * Redirect target for the child process's stdin. The command execution path never writes to the child's input; left as Java's default empty pipe,
         * programs that decide behavior on "is stdin readable" (rg without a path argument, package managers awaiting confirmation, etc.) treat it as
         * pending input and block until the command times out; wiring the null device gives them an immediate EOF so they take the normal branch.
         */
        private val STDIN_FROM_DEV_NULL: ProcessBuilder.Redirect =
            ProcessBuilder.Redirect.from(java.io.File("/dev/null"))

        /** Default command timeout in milliseconds: applied when unspecified, so a hung command never occupies the session forever. */
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /** Maximum command timeout in milliseconds: larger requests are clamped to this, preventing a de facto "infinite wait". */
        const val MAX_TIMEOUT_MS = 1_800_000L

        /** Grace period in milliseconds after timeout before the process is force-killed. */
        private const val TIMEOUT_KILL_GRACE_MS = 200L

        /**
         * Base package provisioning version. Corresponds to the version of assets/xencode/provision.sh: bump +1 in sync when the script changes (package list/install logic/mirror source),
         * which re-runs the script on the device (apk add is idempotent; installed packages are skipped).
         * Independent of [ContainerInstaller]'s rootfs INSTALL_VERSION: a rootfs version upgrade deletes the rootfs
         * (clearing this marker with it), so a new rootfs always re-runs provisioning; script changes under the same rootfs are triggered by this version instead.
         * The marker file is written by the script itself (containing the version below, or the skip marker
         * provision-script-skipped when a custom-image user chooses manual install); the app only reads it to decide completion.
         */
        private const val PROVISION_VERSION = "provision-script-v10"
    }

    /** Marks that base packages have been provisioned per [PROVISION_VERSION] (content is the version, or the skip marker for manual install); stored in the current profile's rootfs directory (built-in/custom kept separate). */
    private fun provisionMarker(profile: ContainerProfile): java.io.File =
        java.io.File(containerInstaller.rootfsDirFor(profile), ".provisioned")

    /**
     * Streams a command inside the container: emits a [CommandEvent.Line] per line read, and a
     * [CommandEvent.Exit] when the command ends. The first call triggers rootfs installation (idempotent).
     *
     * Shares the same process-building logic with [runCommandSync]; the only difference is output is delivered line by line in real time,
     * so the terminal sees the "process" rather than just the final result.
     *
     * [timeoutMs] is the maximum command run time in milliseconds, defaulting to [DEFAULT_TIMEOUT_MS], capped at [MAX_TIMEOUT_MS].
     * On timeout the child process is forcibly terminated and a timeout notice appended; [CommandEvent.Exit] carries a null exit code.
     * Since readLine blocks, a coroutine timeout alone cannot interrupt it; a standalone watchdog destroys the process to break the block.
     */
    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        // When not ready (rootfs not extracted or base packages not provisioned), do not auto-initialize; just prompt the user to finish initialization on the terminal page.
        notReadyHint()?.let {
            emit(CommandEvent.Line(it))
            emit(CommandEvent.Exit(null))
            return@flow
        }
        emitAll(streamExecNoInstall(command, projectPath, timeoutMs))
    }.flowOn(Dispatchers.IO)
    /**
     * The "bare" implementation of streaming a command inside the container: emits a [CommandEvent.Line] per line read, and a
     * [CommandEvent.Exit] when the command ends. **Does not trigger lazy installation** (never calls [ensureInstalled]); assumes the rootfs is ready.
     *
     * Extracted so [runCommandStream] (which calls [ensureInstalled] first) and provisioning-script execution (which calls
     * [ContainerInstaller.installRootfsIfNeed] first and must not re-trigger ensureInstalled, or it would recurse) can share it,
     * letting initialization also receive output line by line to update progress.
     *
     * [timeoutMs] is the maximum command run time in milliseconds, defaulting to [DEFAULT_TIMEOUT_MS], capped at [MAX_TIMEOUT_MS].
     * On timeout the child process is forcibly terminated and a timeout notice appended; [CommandEvent.Exit] carries a null exit code.
     * Since readLine blocks, a coroutine timeout alone cannot interrupt it; a standalone watchdog destroys the process to break the block.
     */
    private fun streamExecNoInstall(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        val effectiveTimeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "Executing command (streaming) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
        val process = startContainerProcess(command, projectPath)
        val timedOut = AtomicBoolean(false)
        // The watchdog runs on its own scope (its own Job): placing it inside the coroutineScope wrapping emit would make emit's
        // Job differ from the flow collector's, triggering "Flow invariant is violated". Here it is only used to kill the process on timeout.
        val watchScope = CoroutineScope(Dispatchers.IO + Job())
        val watchdog = launchKillWatchdog(watchScope, process, effectiveTimeout, timedOut, command)
        val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException && process.isAlive) {
                FileLogger.i(TAG, "Command cancelled, terminating process: ${sanitizeCommandForLog(command)}")
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
                else FileLogger.v(TAG, "Command completed (exit code 0): ${sanitizeCommandForLog(command)}")
                emit(CommandEvent.Exit(exitCode))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The watchdog's timeout destroy closes the stdout pipe, making the blocked readLine throw IOException
            // (instead of returning null). If not absorbed here, the exception terminates the flow abnormally and CommandEvent.Exit is never emitted;
            // the caller's executeStream collect then aborts, and output already shown line by line to the user is lost from the final ToolResult.
            // So branch on timeout here: on timeout emit the timeout notice + Exit(null), keeping the lines already emitted;
            // non-IO exceptions are likewise turned into a one-line notice + Exit so an abnormal flow end cannot drop already-output content.
            watchdog.cancel()
            if (timedOut.get()) {
                FileLogger.w(TAG, "Command timed out (${effectiveTimeout}ms) and was terminated (readLine exception): ${sanitizeCommandForLog(command)}", e)
                emit(CommandEvent.Line(timeoutNotice(effectiveTimeout)))
                emit(CommandEvent.Exit(null))
            } else {
                FileLogger.e(TAG, "Command output read error (earlier output preserved): ${sanitizeCommandForLog(command)}", e)
                emit(CommandEvent.Line("[Command execution error: ${e.message}]"))
                emit(CommandEvent.Exit(null))
            }
        } finally {
            // On coroutine cancellation (user leaving the page, etc.) make sure the child process is reaped, avoiding leaks
            cancellationHook?.dispose()
            watchdog.cancel()
            watchScope.cancel()
            runCatching { reader.close() }
            runCatching { process.destroy() }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Runs a command inside the container synchronously and returns the output. The first call triggers rootfs installation (idempotent).
     *
     * [timeoutMs] is the maximum command run time in milliseconds, defaulting to [DEFAULT_TIMEOUT_MS], capped at [MAX_TIMEOUT_MS].
     * On timeout the child process is forcibly terminated; the collected partial output is returned with a timeout notice appended.
     */
    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = withContext(Dispatchers.IO) {
        // When not ready, do not auto-initialize; return the guidance text directly and let the user finish initialization on the terminal page.
        notReadyHint()?.let { return@withContext it }
        execCaptured(command, projectPath, timeoutMs).output
    }

    /**
     * Same as [runCommandSync], but also returns the exit code (null on timeout/exception). For callers that judge
     * success by the exit code — e.g. git write operations: a non-zero git exit code is not a crash, and [runCommandSync] returning only text would let callers falsely report success.
     */
    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        // When not ready, do not auto-initialize; return the guidance text directly and let the user finish initialization on the terminal page.
        notReadyHint()?.let { return@withContext CommandResult(it, null) }
        val r = execCaptured(command, projectPath, timeoutMs)
        CommandResult(r.output, r.exitCode, r.truncated)
    }

    /** Internal result of one in-container execution: bounded full output + exit code (null on timeout/exception). */
    private data class ExecResult(val output: String, val exitCode: Int?, val truncated: Boolean = false)

    /**
     * Runs a command only when the container is already ready (rootfs installed); never triggers rootfs extraction.
     *
     * Used by read-only tools as a performance enhancement: e.g. search can prefer rg, but one auto-approved search
     * must not implicitly extract the container or install an environment over the network. Returns null when not installed.
     */
    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? {
        if (!containerInstaller.isInstalledFor(currentProfile)) return null
        val result = execCaptured(command, projectPath, timeoutMs)
        return CommandResult(result.output, result.exitCode, result.truncated)
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

    /**
     * Runs a command inside the container synchronously and captures the output. **Assumes the rootfs is installed** (no lazy install/provision),
     * reused by [runCommandSync] (after [ensureInstalled]) and provisioning-script execution (after [installRootfsIfNeed]),
     * so the provisioning flow cannot re-trigger [ensureInstalled] and recurse.
     *
     * [timeoutMs] is the maximum command run time in milliseconds, clamped to [MAX_TIMEOUT_MS]. On timeout the process is force-killed,
     * the collected partial output is returned with a timeout notice appended, and [ExecResult.exitCode] is null.
     */
    private suspend fun execCaptured(
        command: String,
        projectPath: String?,
        timeoutMs: Long,
        unbounded: Boolean = false
    ): ExecResult = withContext(Dispatchers.IO) {
        try {
            val effectiveTimeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
            FileLogger.d(TAG, "Executing command (sync) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
            val process = startContainerProcess(command, projectPath)
            val timedOut = AtomicBoolean(false)
            val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException && process.isAlive) {
                    FileLogger.i(TAG, "Command cancelled, terminating process: ${sanitizeCommandForLog(command)}")
                    runCatching { process.destroy() }
                    runCatching { process.destroyForcibly() }
                }
            }

            // Bounded accumulation: oversized output keeps only head+tail, protecting memory and model context.
            // Unbounded mode keeps a continuous head, up to MAX_UNBOUNDED_CHARS,
            // stopping and setting truncated past that (callers like git use it to show "output too large").
            val output = if (unbounded) BoundedOutput.hardCapped(MAX_UNBOUNDED_CHARS) else BoundedOutput()
            // The watchdog runs concurrently with the read loop: on timeout it destroys the process so the blocked readLine immediately returns null and exits the loop.
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
                FileLogger.v(TAG, "Command completed (exit code $exitCode, output ${output.totalChars} chars): ${sanitizeCommandForLog(command)}")
                ExecResult(output.build(), exitCode, output.truncated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Command execution error: ${sanitizeCommandForLog(command)}", e)
            ExecResult("Error: ${e.message}", null)
        }
    }

    /**
     * Starts the watchdog: after [timeoutMs], if the process is still alive, marks the timeout and terminates it gracefully→forcibly,
     * unblocking the caller's readLine. The returned [Job] is cancelled by the caller on normal completion.
     *
     * **Pauses while a credential popup is in flight**: when the deadline hits, if [credentialPromptInFlight] > 0 (the custom git credential helper
     * is blocked waiting for the app popup to fill in the user's credentials), the watchdog does not kill — this git command is "waiting for credentials",
     * not "hung"; killing at the normal timeout would make the user come back minutes later to find the push already failed and start over. Instead it re-checks each round:
     * while in flight, grant a grace period (1min) and re-check, and only kill once nothing is in flight; the absolute cap [MAX_TIMEOUT_MS] (30min) kills even then
     * to avoid a de facto infinite wait. The terminal PTY path bypasses the watchdog ([TerminalSessionManager] runs a bare tty) and naturally tolerates waiting best.
     */
    private fun launchKillWatchdog(
        scope: CoroutineScope,
        process: Process,
        timeoutMs: Long,
        timedOut: AtomicBoolean,
        command: String
    ): Job = scope.launch {
        var remaining = timeoutMs
        var totalWaited = 0L
        while (true) {
            delay(remaining)
            if (!process.isAlive) return@launch
            totalWaited += remaining
            val inflight = credentialPromptInFlight.get()
            if (inflight > 0 && totalWaited < MAX_TIMEOUT_MS) {
                // Credential popup in flight: grant a 1min grace period (not past the absolute cap) and re-check.
                FileLogger.i(TAG, "Credential popup in flight ($inflight), watchdog deferring, waiting another 60000ms: ${sanitizeCommandForLog(command)}")
                remaining = minOf(60_000L, MAX_TIMEOUT_MS - totalWaited)
                continue
            }
            // Not in flight, or the 30min absolute cap reached: terminate as a normal timeout.
            timedOut.set(true)
            FileLogger.w(TAG, "Command exceeded ${timeoutMs}ms (total waited ${totalWaited}ms, inflight=${inflight}), terminating process: ${sanitizeCommandForLog(command)}")
            runCatching { process.destroy() }
            delay(TIMEOUT_KILL_GRACE_MS)
            if (process.isAlive) runCatching { process.destroyForcibly() }
            return@launch
        }
    }

    /** Timeout notice line (appended to the output, fed back to the model/shown to the user). */
    private fun timeoutNotice(timeoutMs: Long): String =
        "[Command timed out: exceeded ${timeoutMs}ms and was forcibly terminated]"

    /**
     * Starts the process: if rootfs/proot is installed, enter the container with PRoot; **if not ready, refuse to run**.
     *
     * There used to be a "fall back to the Android native shell" path here ([buildNativeProcess],
     * `/system/bin/sh -c` + working directory set to the workspace). That was a security hole: users pick the local container channel
     * for the "sandbox"; if the rootfs were never extracted, commands would **silently** run on the device shell instead,
     * with only a single warning log — appearing as "I clearly used the container channel, yet the command took effect on the device".
     * A sandbox promise must not be silently downgraded, so it is now a hard failure: throw when not ready and let callers turn it into an explicit error output.
     */
    private fun startContainerProcess(command: String, projectPath: String?): Process {
        val profile = currentProfile
        if (!containerInstaller.isInstalledFor(profile)) {
            val hint = notReadyHintFor(profile) ?: "Container is not initialized yet"
            FileLogger.e(
                TAG,
                "Container not ready, refusing to run (native shell fallback is disabled): ${sanitizeCommandForLog(command)}"
            )
            throw IllegalStateException(hint)
        }

        val processBuilder = buildProcessBuilder(buildProotInvocation(command, projectPath))

        // Redirect stderr to stdout so we capture everything in one stream
        processBuilder.redirectErrorStream(true)
        processBuilder.redirectInput(STDIN_FROM_DEV_NULL)
        return processBuilder.start()
    }

    /** Whether the container is installed and ready (per the current profile). */
    override fun isContainerInstalled(): Boolean = containerInstaller.isInstalledFor(currentProfile)

    /**
     * Whether base packages are provisioned (per the current profile's marker). On first terminal entry the provisioning menu
     * (assets/xencode/provision.sh) guides the install and the script writes the marker when done; incompleteness does not block shell entry.
     */
    override fun isProvisioned(): Boolean = isProvisionedFor(currentProfile)

    /** Whether base packages are provisioned for the given profile ([defaultShell] picks bash/sh based on this). */
    fun isProvisionedFor(profile: ContainerProfile): Boolean {
        val marker = provisionMarker(profile)
        return marker.exists() && marker.readText().trim() == PROVISION_VERSION
    }

    /**
     * Returns guidance text when the container is not ready (rootfs not extracted); null when ready.
     * Command execution entry points use it to fail directly instead of auto-initializing, guiding the user to the terminal page.
     */
    override fun notReadyHint(): String? = notReadyHintFor(currentProfile)

    /** Whether the container for the given profile is ready (used by MCP's pre-launch check for runtime containers). */
    fun notReadyHintFor(profile: ContainerProfile): String? {
        if (containerInstaller.isInstalledFor(profile)) return null
        return context.getString(R.string.container_not_ready_hint)
    }

    /**
     * The container's default command shell: prefers the profile's [ContainerProfile.shellPath]; when unset,
     * picks `/bin/bash` (the script installed bash) or `/bin/sh` per provision state.
     */
    override fun defaultShell(): String {
        val profile = currentProfile
        profile.shellPath?.takeIf { it.isNotBlank() }?.let { return it }
        return if (isProvisioned()) "/bin/bash" else "/bin/sh"
    }

    /**
     * Idempotently ensures the container is usable: extracts rootfs/proot (slow the first time). Uniform for all containers (built-in/custom) —
     * base tool installation is not run automatically in this phase; the provisioning menu on terminal entry (assets/xencode/provision.sh)
     * guides the user's choice, and a failed install does not block shell entry.
     * Called only by the terminal page ([TerminalSessionManager]) as the sole initialization entry point; command execution entry points no longer trigger it.
     *
     * The slow initialization (extraction) runs in the engine-level [initScope] and is not interrupted when the caller
     * (the terminal page's viewModelScope) is cancelled — after leaving the terminal page initialization keeps going in the background, and the next entry reuses the same job or awaits it.
     * Stage progress is reported throughout via [initProgress]. On rootfs extraction failure it sets [ContainerInitState.Failed]
     * and throws (instead of silently setting Ready), putting the terminal page into a retryable failure state.
     */
    override suspend fun ensureInstalled() {
        val profile = currentProfile
        val startedAt = System.currentTimeMillis()
        FileLogger.i(
            TAG,
            "ensureInstalled start: profile=${profile.id}(${profile.name}) mode=${profile.mode} " +
                "installed=${containerInstaller.isInstalledFor(profile)}"
        )
        // Make sure the latest built-in docs are extracted before each terminal page entry
        containerInstaller.extractDocs()
        if (containerInstaller.isInstalledFor(profile)) {
            // Old rootfs (e.g. an imported Ubuntu 26.04) is not re-extracted; run the compatibility inspection here to fix known issues
            containerInstaller.repairRootfsCompatibility(containerInstaller.rootfsDirFor(profile))
            _initProgress.value = ContainerInitState.Ready
            refreshContainerHome()
            detectAndCacheOsIfNeeded(profile)
            FileLogger.i(TAG, "ensureInstalled done (fast path, took ${System.currentTimeMillis() - startedAt}ms)")
            logContainerDiagnostics("ensureInstalled fast path")
            return
        }
        // Start or reuse the background initialization job; initMutex only guards job creation/reuse — the slow work runs in initScope.
        val job = initMutex.withLock {
            val existing = initJob
            if (existing == null || !existing.isActive) {
                FileLogger.i(TAG, "Created background initialization job: profile=${profile.id}")
                initJob = initScope.launch { doInit(profile) }
            } else {
                FileLogger.i(TAG, "Reusing in-progress background initialization job: profile=${profile.id}")
            }
            initJob!!
        }
        // Exceptions thrown inside the background job are not rethrown by join; attach a completion callback to record them,
        // so the log does not just stop after "install started" with no follow-up.
        job.invokeOnCompletion { cause ->
            if (cause != null) FileLogger.e(TAG, "Background initialization job ended abnormally: profile=${profile.id}", cause)
        }
        // Await completion; if the caller (terminal page) is cancelled, join throws CancellationException, but the background job keeps running.
        job.join()
        if (containerInstaller.isInstalledFor(profile)) {
            _initProgress.value = ContainerInitState.Ready
            refreshContainerHome()
            detectAndCacheOsIfNeeded(profile)
            FileLogger.i(TAG, "ensureInstalled done (took ${System.currentTimeMillis() - startedAt}ms)")
            logContainerDiagnostics("after ensureInstalled")
        } else {
            val reason = "Container is not installed (rootfs/proot missing)"
            FileLogger.e(TAG, "ensureInstalled failed: $reason (profile=${profile.id})")
            logContainerDiagnostics("ensureInstalled failed")
            _initProgress.value = ContainerInitState.Failed(reason)
            throw IllegalStateException(reason)
        }
    }

    /**
     * Dumps the container runtime environment (rootfs / proot binaries / provisioning script / disk space) to the log.
     *
     * These states are not normally printed, so a report of "container won't start / blank terminal" would be pure guesswork. Dumping once proactively before/after initialization and when a terminal
     * session goes silent leaves locatable evidence in the log. It also includes the tail of provision.sh's own execution log
     * (written by the script to /root/.xencode/provision.log), used to tell whether the provisioning menu ever ran.
     */
    fun logContainerDiagnostics(reason: String) {
        runCatching {
            val profile = currentProfile
            val rootfs = containerInstaller.rootfsDirFor(profile)
            val marker = provisionMarker(profile)
            val provision = java.io.File(containerInstaller.xencodeDir, "provision.sh")
            fun desc(f: java.io.File?): String = when {
                f == null -> "null"
                !f.exists() -> "missing(${f.absolutePath})"
                f.isDirectory -> "dir(${f.absolutePath})"
                else -> "${f.absolutePath}[${f.length()}B,executable=${f.canExecute()}]"
            }
            val space = runCatching { rootfs.usableSpace / (1024 * 1024) }.getOrDefault(-1L)
            val ptmx = java.io.File("/dev/ptmx")
            FileLogger.i(
                TAG,
                "Container diagnostics ($reason): profile=${profile.id} mode=${profile.mode} rootfs=${desc(rootfs)} " +
                    "marker=${if (marker.isFile) marker.readText().trim() else "none"} " +
                    "proot=${desc(containerInstaller.prootBin)} loader=${desc(containerInstaller.prootLoader)} " +
                    "loader32=${desc(containerInstaller.prootLoader32)} provision.sh=${desc(provision)} " +
                    "nativeLibDir=${containerInstaller.prootBin.parentFile?.absolutePath} usableSpace=${space}MB " +
                    "/dev/ptmx(readable=${ptmx.canRead()},writable=${ptmx.canWrite()})"
            )
            val provisionLog = java.io.File(containerInstaller.xencodeDir, "provision.log")
            if (provisionLog.isFile) {
                val tail = runCatching { provisionLog.readLines().takeLast(20).joinToString(" | ") }.getOrDefault("")
                FileLogger.i(TAG, "provision log tail ($reason): $tail")
            } else {
                FileLogger.i(TAG, "provision.log does not exist (provisioning script never ran): ${provisionLog.absolutePath}")
            }
        }.onFailure { FileLogger.w(TAG, "Failed to dump container diagnostics", it) }
    }

    /** Performs the actual one-time initialization in [initScope]: extracts the rootfs (base tools are guided in by the provisioning menu on terminal entry). */
    private suspend fun doInit(profile: ContainerProfile) {
        // installRootfsIfNeed reports progress when it actually extracts/deploys (the already-installed fast path does not)
        containerInstaller.installRootfsIfNeed(profile) { _initProgress.value = it }
    }

    /** Looks up $HOME inside the container and caches it in [com.xencode.feature.workspace.domain.PathHomeResolver] for tools to expand ~. */
    private suspend fun refreshContainerHome() {
        runCatching {
            val result = execCaptured("echo \$HOME", projectPath = null, timeoutMs = 3000)
            val home = result.output.trim().ifEmpty { null }
            if (home != null) pathHomeResolver.containerHome = home
        }.onFailure { FileLogger.w(TAG, "Failed to look up container \$HOME", it) }
    }

    /**
     * Detects the OS type after the container's first run (reading the ID field of /etc/os-release) and caches it;
     * skipped when already cached or when detection fails — retried on the next run. Remote SSH has no local rootfs and is not detected.
     */
    private suspend fun detectAndCacheOsIfNeeded(profile: ContainerProfile) {
        if (profile.mode == ExecutionMode.REMOTE_SSH) return
        if (containerOsDetector.cachedOs(profile.id) != null) return
        runCatching {
            val result = execCaptured(
                "if [ -f /etc/os-release ]; then . /etc/os-release 2>/dev/null; echo \"\$ID\"; fi",
                projectPath = null,
                timeoutMs = 3000
            )
            val osId = result.output.trim().lowercase().ifBlank { null }
            if (osId != null && result.exitCode == 0) {
                containerOsDetector.cacheOs(profile.id, osId)
                FileLogger.i(TAG, "Container OS detected: ${profile.id} -> $osId")
            }
        }.onFailure { FileLogger.w(TAG, "Container OS detection failed: ${profile.id}", it) }
    }

    /**
     * Runs a command inside the container as a long-lived process (e.g. `sshd -D`). The container must be installed beforehand.
     * Unlike [runCommandStream], output streams are not read/consumed here; the caller decides what to do with them (usually discard).
     */
    fun startProotProcess(command: String, projectPath: String?): Process {
        val pb = buildProcessBuilder(buildProotInvocation(command, projectPath))
        pb.redirectErrorStream(true)
        pb.redirectInput(STDIN_FROM_DEV_NULL)
        return pb.start()
    }

    /**
     * Starts a program inside the container as a long-lived process with **separated** stdin/stdout/stderr, so the caller
     * can do bidirectional streaming (e.g. an MCP stdio server: write JSON-RPC to stdin, read JSON-RPC from stdout).
     *
     * Key difference from [startProotProcess]: does **not** redirectErrorStream; stderr is kept separate,
     * keeping stdout a clean protocol stream (the server's logs go to stderr and never pollute the JSON-RPC).
     *
     * [program] is the in-container executable name/path (e.g. `npx`) and [programArgs] its arguments; both are passed through
     * `/bin/sh -c 'exec "$0" "$@"'` item by item to avoid shell quoting/escaping issues. [extraEnv] is layered onto the
     * container's default environment (overriding same-named entries). The container must be installed beforehand ([ensureInstalled]).
     */
    fun startStdioProcess(
        program: String,
        programArgs: List<String>,
        projectPath: String?,
        extraEnv: Map<String, String> = emptyMap(),
        profile: ContainerProfile = currentProfile
    ): Process {
        val invocation = buildStdioInvocation(program, programArgs, projectPath, extraEnv, profile)
        val pb = buildProcessBuilder(invocation)
        // Deliberately no redirectErrorStream: stdout is reserved for JSON-RPC; stderr is consumed separately by the caller.
        // Also deliberately no stdin redirect: stdin here is the JSON-RPC request channel; wiring it to /dev/null would make the server read EOF and exit immediately.
        return pb.start()
    }

    /**
     * Builds the PRoot invocation that "execs a program directly inside the container (with separated streams)".
     * Uses `sh -c 'exec "$0" "$@"' program arg1 arg2 …` to hand the arguments to execvp verbatim, dodging quoting issues.
     */
    private fun buildStdioInvocation(
        program: String,
        programArgs: List<String>,
        projectPath: String?,
        extraEnv: Map<String, String>,
        profile: ContainerProfile
    ): ProotInvocation {
        val argv = buildBaseProotArgv(projectPath, profile)
        argv.add("/bin/sh")
        argv.add("-c")
        argv.add("exec \"\$0\" \"\$@\"")
        argv.add(program)
        argv.addAll(programArgs)
        return ProotInvocation(argv, buildContainerEnv(profile) + profile.env + extraEnv)
    }

    /**
     * Builds the full PRoot invocation (argv + env) that enters the container to run [command].
     * Exposed for terminal sessions: Termux TerminalSession needs the executable and arguments passed separately.
     */
    fun buildProotInvocation(command: String, projectPath: String?): ProotInvocation {
        val argv = buildBaseProotArgv(projectPath, currentProfile)
        // Uses [defaultShell]: once bash is installed, both AI commands and terminal sessions use bash; during provisioning or on failure it falls back to /bin/sh.
        argv.add(defaultShell())
        argv.add("-c")
        argv.add(command)
        return ProotInvocation(argv, buildContainerEnv(currentProfile) + currentProfile.env)
    }

    /**
     * Builds the shared prefix argv for PRoot invocations: proot binary + rootfs + standard bindings + fake root + workspace binding,
     * but **excluding** the final guest command (each caller appends its own `/bin/sh -c …` or `exec` form).
     */
    private fun buildBaseProotArgv(projectPath: String?, profile: ContainerProfile): MutableList<String> {
        val rootfs = containerInstaller.rootfsDirFor(profile).absolutePath
        val prootBin = containerInstaller.prootBin.absolutePath
        if (!containerInstaller.prootBin.exists()) {
            // Cannot go missing unless the packaging config regressed: if the jniLibs sourceSet or
            // packaging.jniLibs.useLegacyPackaging was lost, nativeLibraryDir is empty and proot reports ENOENT with no hint of the real cause.
            FileLogger.e(TAG, "proot missing from nativeLibraryDir: $prootBin (check jniLibs and useLegacyPackaging)")
        }
        val argv = mutableListOf(
            prootBin,
            "-r", rootfs,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/system",  // Bind /system so host dynamic libraries are usable
            "-0"              // Fake root; needed by apk and friends
        )

        // Binds the current workspace directory to ~/workspace inside the container (i.e. /root/workspace), so commands and file tools act on the same directory
        if (projectPath != null) {
            argv.add("-b")
            argv.add("$projectPath:/root/workspace")
            argv.add("-w")
            argv.add("/root/workspace")
        }

        // Binds the AI config directory to /root/.xencode inside the container (read-write): it holds skills/ (instructions read by load_skill often reference
        // scripts in the skill directory, which the AI runs via execute_command, e.g. `python /root/.xencode/skills/<name>/x.py`) and
        // mcp.json (MCP config). The host directory is physically independent of the rootfs, so container upgrades/reinstalls never lose user data.
        // The base interpreters python3(3.12) and git are installed by the provisioning menu (provision.sh) on terminal entry;
        // other runtimes like node remain the skill's/user's responsibility. proot's -b requires the source path to exist, so make sure the directory is created first.
        val xencodeDir = containerInstaller.xencodeDir.apply { mkdirs() }
        argv.add("-b")
        argv.add("${xencodeDir.absolutePath}:/root/.xencode")

        // The profile's extra bindings and arguments: defaults for built-in and imported containers are injected here too (see ContainerProfile.DEFAULT_PROOT_ARGS),
        // through the same path as manually added ones, so the arguments land at the end of the argv.
        for (b in profile.extraBindings) {
            argv.add("-b")
            argv.add(b)
        }
        argv.addAll(profile.extraArgs)

        return argv
    }

    /** Standard environment variables for in-container processes (proot loader / dynamic libraries / PATH / HOME, etc.). */
    private fun buildContainerEnv(profile: ContainerProfile): Map<String, String> {
        // Each container uses the /tmp under its own rootfs: the image ships that directory, but resetting/deleting another container must not affect this one,
        // and proot requires PROOT_TMP_DIR to already exist, so make sure it is created before launch (same treatment as -b source paths).
        val tmpDir = containerInstaller.prootTmpDirFor(profile).apply { mkdirs() }
        return mapOf(
            // Environment variables required by Android proot
            "PROOT_TMP_DIR" to tmpDir.absolutePath, // Android has no /tmp
            // Termux's proot separates its loaders; they must be pointed to via PROOT_LOADER/_32, otherwise the child process cannot be injected and proot fails to start.
            "PROOT_LOADER" to containerInstaller.prootLoader.absolutePath,
            "PROOT_LOADER_32" to containerInstaller.prootLoader32.absolutePath,
            // Termux's proot dynamically links libtalloc.so / libandroid-shmem.so (living in nativeLibraryDir alongside proot),
            // so linker64 must be able to find them; libc.so/liblog.so resolve via the system default path (/system/lib64).
            "LD_LIBRARY_PATH" to "${containerInstaller.prootLibDir.absolutePath}:/system/lib64:/system/lib",
            // Note (statx / seccomp): old proot 5.1.0's seccomp filter table lacks statx, so Node resolving
            // a module path via statx gets the untranslated ~/workspace/xxx → ENOENT "Cannot find module". Termux proot
            // (5.1.107.x) already includes statx in its seccomp filter table, and the default seccomp mode translates correctly, so
            // **PROOT_NO_SECCOMP is deliberately unset** — this is exactly how Termux itself uses proot; forcing full ptrace
            // (PROOT_NO_SECCOMP=1) actually triggered a ptrace(PEEKDATA) I/O error on this device.
            // The /root/.xencode/bin prefix: the in-container xencode environment tooling (see ContainerInstaller.extractEnvTool).
            "PATH" to "/root/.xencode/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            "HOME" to "/root",
            // The host process TMPDIR points to the app cache directory (/data/user/0/<pkg>/cache); /data is not mounted in the container,
            // so that path does not exist there — mktemp/dpkg etc. would fail to find a temp directory, hence the explicit override to the in-container /tmp.
            "TMPDIR" to "/tmp",
            // The git global config points at the .gitconfig in the persistent mount (/root/.xencode binds to the host filesDir/xencode,
            // surviving rootfs upgrades). git-credentials lives in the same directory, read by credential.helper=store;
            // the credential.helper is registered by provision.sh via includeIf scoped to /root/workspace/ (minimal injection).
            "GIT_CONFIG_GLOBAL" to "/root/.xencode/.gitconfig",
            // safe.directory=* turns off git's dubious ownership check. User-chosen external workspaces live on
            // sdcardfs/FUSE storage, whose synthesized file owner uid differs from proot's euid(0); git deems the owner suspicious
            // and fatally refuses operations — `git init` succeeds (init skips the ownership check) but every later `git rev-parse` fails,
            // leaving the Git page stuck on "not initialized". The container is a single-user sandbox with no cross-user repositories, so allowing all is safe.
            // Injected via GIT_CONFIG_COUNT rather than written into .gitconfig: it takes effect immediately even for already-initialized containers and does not touch user config files.
            // The Git page / terminal / agent all start through this environment, so one override covers every local git entry point.
            "GIT_CONFIG_COUNT" to "1",
            "GIT_CONFIG_KEY_0" to "safe.directory",
            "GIT_CONFIG_VALUE_0" to "*",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            // Timezone follows the phone: container images hard-code UTC by default (/etc/localtime → Etc/UTC); with TZ injected,
            // date/Python/Node/Go/JVM etc. display in the phone's current timezone; commands started after a phone timezone change pick it up immediately.
            "TZ" to containerTimeZone(profile)
            // Global HTTP proxy: when enabled, HTTP_PROXY/HTTPS_PROXY/ALL_PROXY/NO_PROXY are injected,
            // so curl/git/npm/pip etc. inside the container all go through the proxy; when disabled or incomplete, an empty map leaves direct connections untouched.
        ) + com.xencode.core.net.AppProxy.proxyEnv(context)
    }

    /**
     * The in-container timezone (the TZ environment value), following the phone's current system timezone.
     *
     * glibc honors TZ and prefers it over /etc/localtime; once injected, all local commands in the container display in the phone's timezone.
     * The IANA name is preferred (it carries DST rules), but the file must actually exist in the rootfs's /usr/share/zoneinfo — Alpine
     * and other slim images ship without tzdata, and the system may return a non-IANA name like "GMT+08:00"; in those cases it degrades to
     * a POSIX spelling generated from the current UTC offset (e.g. `<UTC+8>-8`) — no DST handling, but it always works.
     */
    private fun containerTimeZone(profile: ContainerProfile): String {
        val timeZone = java.util.TimeZone.getDefault()
        val zoneInfoFile = java.io.File(
            containerInstaller.rootfsDirFor(profile),
            "usr/share/zoneinfo/${timeZone.id}"
        )
        if (zoneInfoFile.isFile) return timeZone.id

        // localMinutes is the offset from UTC (UTC+8 = +480). The name is written in the intuitive direction, UTC+8,
        // while POSIX offset signs run the opposite way (UTC+8 is written -8); if the two shared a sign it would display "UTC-8" and be misread as UTC-8.
        // The name only takes whole hours (musl's std name rejects ':'), while the offset keeps the full hh:mm so minute-level timezones stay exact.
        val localMinutes = timeZone.getOffset(System.currentTimeMillis()) / 60_000
        if (localMinutes == 0) return "UTC0"
        val absMinutes = Math.abs(localMinutes)
        val hours = absMinutes / 60
        val minutes = absMinutes % 60
        val localSign = if (localMinutes > 0) "+" else "-"
        val posixSign = if (localMinutes > 0) "-" else "+"
        val posixOffset = if (minutes == 0) "$hours" else "%d:%02d".format(hours, minutes)
        return "<UTC$localSign$hours>$posixSign$posixOffset"
    }

    private fun buildProcessBuilder(invocation: ProotInvocation): ProcessBuilder {
        val processBuilder = ProcessBuilder(invocation.argv)
        processBuilder.environment().putAll(invocation.env)
        return processBuilder
    }

    // The former buildNativeProcess (/system/bin/sh native fallback) has been deleted: the container channel must not silently downgrade to the device shell,
    // see the notes on startContainerProcess. For on-device execution, go explicitly through DeviceRootEngine (the DEVICE_ROOT channel).
}
