package com.xencode.feature.terminal.domain

import android.content.Context
import android.content.Intent
import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.container.LinuxContainerEngine
import com.xencode.feature.agent.domain.device.DeviceRootEngine
import com.xencode.feature.settings.data.repository.ExecutionMode
import com.xencode.feature.settings.data.repository.ExecutionModeHolder
import com.xencode.feature.terminal.presentation.component.AppTerminalSessionClient
import com.xencode.feature.workspace.data.repository.WorkspaceRepository
import com.xencode.feature.workspace.domain.PathHomeResolver
import com.termux.terminal.TerminalSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process resident terminal session pool: the single owner of all [TerminalSession]s.
 *
 * It lives in a [Singleton] instead of a ViewModel: terminals must "stay resident in the background", and leaving the terminal page or switching to chat must not kill sessions.
 * ViewModels are bound to navigation routes and call onCleared when popped, which would finishIfRunning and kill proot — so session
 * ownership is hoisted into this manager and the ViewModel degrades to a read-only observer. As long as the app process lives, sessions keep running.
 *
 * Each tab has a stable, AI-friendly unique id (`term-N`). By id the AI can:
 *  - [startBackgroundCommand] run something like `npm run dev` in the background and get the id;
 *  - [sendInput] keep sending commands by id;
 *  - [writeBytesToTab] send control characters by id (e.g. Ctrl-C=0x03);
 *  - [closeTab] close and destroy a session by id;
 *  - [getTabOutput] read terminal content by id (emulator screen buffer).
 *
 * All mutable state reads/writes happen on the main thread (UI events, AI workflow calls dispatched to the main thread), so no extra locking.
 */
@Singleton
class TerminalSessionManager @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val containerEngine: LinuxContainerEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val modeHolder: ExecutionModeHolder,
    private val deviceRootEngine: DeviceRootEngine,
    private val pathHomeResolver: PathHomeResolver
) : TerminalSessionProvider {
    private companion object {
        const val TAG = "TerminalSessionManager"
        const val TRANSCRIPT_ROWS = 2000
        // Default terminal size for sessions started "in place" without a mounted view; resized to the real size once a view mounts.
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24
        /** Buffer (ms) after the command prints `[command exited: N]` while waiting for the normal onFinished callback. */
        const val EXIT_MARKER_GRACE_MS = 1_500L
        /** Interval (ms) for the completion fallback monitor polling the screen buffer. The old 200ms built a 2000-line transcript string every round,
         *  hogging CPU during long commands (builds/installs); relaxed to 1s plus "skip scanning when output has not grown", the overhead is negligible. */
        const val EXIT_MARKER_POLL_MS = 1_000L
        /** Locating prefix matching the command exit marker `[command exited: N]` (paired with manual exit-code parsing, avoiding a full regex scan). */
        const val EXIT_MARKER_PREFIX = "[command exited: "
        /** How long after creating an interactive tab with no output before dumping container diagnostics (proactive forensics when proot failed to start/hangs). */
        const val SILENT_DIAGNOSTIC_DELAY_MS = 10_000L
        /** Candidate paths for the su binary in device ROOT mode (per common Magisk/KernelSU/SuperSU layouts). */
        val SU_CANDIDATES = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/data/adb/ksu/bin/su", "/system/bin/failsafe/su"
        )
    }

    /** Whether the current channel is device ROOT (PTY started via `su` instead of proot). */
    private fun isDevice(): Boolean = modeHolder.currentMode() == ExecutionMode.DEVICE_ROOT

    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    /** Incremented on any tab output/state change so Compose recomposition pulls the latest screen content. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _tabFinishedEvents = MutableSharedFlow<TabFinishedEvent>(extraBufferCapacity = 16)
    override val tabFinishedEvents: SharedFlow<TabFinishedEvent> = _tabFinishedEvents.asSharedFlow()

    private val idCounter = AtomicInteger(0)

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeTab: TerminalTab? get() = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    fun tab(id: String): TerminalTab? = _tabs.value.firstOrNull { it.id == id }

    /** Whether any background terminal tab is still running (used to decide whether the KeepaliveService can stop). */
    fun hasBackgroundTabs(): Boolean =
        _tabs.value.any { it.isBackground && it.runState is RunState.Running }

    /** Called when entering the terminal page: creates an interactive shell if there are no tabs. Idempotent. */
    suspend fun ensureInitialTab() {
        if (_tabs.value.isEmpty()) {
            createInteractiveTab()
        } else if (_activeTabId.value == null) {
            _activeTabId.value = _tabs.value.first().id
        }
    }

    /**
     * Create a new interactive shell tab and make it active. Returns the new tab id.
     *
     * The first call triggers rootfs/proot extraction (idempotent); failures throw for the caller to handle.
     */
    suspend fun createInteractiveTab(): String = createTab()

    private suspend fun createTab(): String {
        FileLogger.i(TAG, "New interactive terminal tab: preparing execution environment (device=${isDevice()})")
        ensureContainer()
        val id = nextId()
        val shellCommand = buildInteractiveCommand()
        FileLogger.i(TAG, "Interactive shell command ($id): $shellCommand")
        val (session, client) = try {
            buildSession(shellCommand)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to create interactive terminal session ($id)", e)
            if (isDevice()) {
                FileLogger.e(TAG, "Device mode su probe=${deviceRootEngine.reprobeRoot()}; if ROOT is unavailable, run detection in settings")
            } else {
                containerEngine.logContainerDiagnostics("Failed to create interactive terminal session")
            }
            throw e
        }
        addTab(
            TerminalTab(
                id = id,
                title = id,
                session = session,
                isBackground = false,
                command = null,
                client = client,
                runState = RunState.Running
            )
        )
        _activeTabId.value = id
        FileLogger.i(TAG, "New interactive terminal tab $id: pid=${session.pid} running=${session.isRunning}")
        scheduleSilentSessionDiagnostic(id)
        return id
    }

    /**
     * Interactive shell startup command. -w already sets cwd to /root/workspace; cd is just a fallback; a bare sh/bash auto-enters interactive mode on a tty,
     * loads the login environment via ENV=/etc/profile; exec lets the shell replace the outer sh -c as the foreground interactive shell.
     * The first-run init menu runs at startup (the script itself exits immediately if already done/skipped); `;` separators ensure any script failure never blocks entering the shell.
     *
     * Device ROOT mode: cwd is set to the real workspace by buildSession; HOME points to the app-private filesDir
     * (where the `workspace`/`.xencode` symlinks are maintained by [DeviceRootEngine.refreshWorkspaceLinks]);
     * PS1 uses mksh parameter expansion (`$PWD`), and exec /system/bin/sh finishes into interactive mode.
     */
    private fun buildInteractiveCommand(): String {
        if (isDevice()) {
            val ws = workspaceRepository.currentPath()
            val home = pathHomeResolver.home()
            return "cd ${DeviceRootEngine.shellQuote(ws)} 2>/dev/null; " +
                "export HOME=${DeviceRootEngine.shellQuote(home)}; " +
                "export PS1='\$PWD # '; " +
                "alias ls='ls --color=auto' 2>/dev/null; alias ll='ls -la' 2>/dev/null; " +
                "exec ${deviceRootEngine.defaultShell()}"
        }
        val startup = "[ -f /root/.xencode/provision.sh ] && sh /root/.xencode/provision.sh; "
        return "cd ~/workspace 2>/dev/null; export ENV=/etc/profile; " +
            startup +
            "export PS1='\\[\\033[01;32m\\]\\u@\\h\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]\\$ '; " +
            "alias ls='ls --color=auto' 2>/dev/null; alias grep='grep --color=auto' 2>/dev/null; alias ll='ls -la --color=auto' 2>/dev/null; " +
            "exec ${containerEngine.defaultShell()}"
    }

    /**
     * Silent session diagnostics: if an interactive tab produces no output long after creation, proot may have failed to start or be stuck in kernel mode
     * (the user-reported "terminal blank and stuck after extraction finishes" is exactly this shape). Proactively dump the container state once
     * so the logs keep locatable evidence, instead of just "new tab" followed by nothing.
     */
    private fun scheduleSilentSessionDiagnostic(tabId: String) {
        monitorScope.launch {
            delay(SILENT_DIAGNOSTIC_DELAY_MS)
            val tab = tab(tabId) ?: return@launch
            if (tab.runState !is RunState.Running) return@launch
            if (!getTabOutput(tabId).isNullOrBlank()) return@launch
            if (isDevice()) {
                FileLogger.w(TAG, "Terminal tab $tabId still has no output ${SILENT_DIAGNOSTIC_DELAY_MS}ms after start (device mode), su probe=${deviceRootEngine.reprobeRoot()}")
            } else {
                FileLogger.w(TAG, "Terminal tab $tabId still has no output ${SILENT_DIAGNOSTIC_DELAY_MS}ms after start, dumping container state")
                containerEngine.logContainerDiagnostics("Terminal tab $tabId silent")
            }
        }
    }

    /**
     * Interface reserved for the AI: run a command in the background (e.g. `npm run dev`) and return a unique tabId.
     *
     * After the command finishes, `exec /bin/sh` keeps it alive so the tab remains a session that can take input (reusable even after the dev server exits),
     * and all output stays in the emulator buffer, visible to the user after switching over or to the AI via [getTabOutput].
     */
    override suspend fun startBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?
    ): String {
        ensureContainer()
        val id = nextId()
        // Capture the command's real exit code with `ec=$?` before echoing; otherwise echo itself is always 0 and overwrites the process exit code,
        // making exitStatus in the onFinished callback forever 0 and mismatching the code echoed on screen.
        // notify=true: after the echo, `exit $ec` ends the shell with the command's real exit code -> passed through by proot -> the callback's exitStatus
        //   is correct, and the shell exiting naturally reliably triggers MSG_PROCESS_EXITED; otherwise the script ends with echo, the process exit code gets
        //   polluted to 0, and in some cases an unclean exit also leaves the callback untriggered.
        // notify=false: after the echo, `exec ${shell}` keeps the tab alive and reusable (input still possible after the dev server exits);
        //   the process does not exit and the callback never fires, the exit code is meaningless — by design for the dev server scenario.
        val afterCommand = if (notify) "; exit \$ec" else "; exec ${if (isDevice()) deviceRootEngine.defaultShell() else containerEngine.defaultShell()}"
        val shellCommand = if (isDevice()) {
            "cd ${DeviceRootEngine.shellQuote(workspaceRepository.currentPath())} 2>/dev/null; " +
                "export HOME=${DeviceRootEngine.shellQuote(pathHomeResolver.home())}; " +
                "$command; ec=\$?; echo \"[command exited: \$ec]\"$afterCommand"
        } else {
            "cd ~/workspace 2>/dev/null; export ENV=/etc/profile; " +
                "$command; ec=\$?; echo \"[command exited: \$ec]\"$afterCommand"
        }
        val (session, client) = buildSession(shellCommand)
        addTab(
            TerminalTab(
                id = id,
                title = title ?: id,
                session = session,
                isBackground = true,
                command = command,
                notifyOnExit = notify,
                sourceSessionId = sourceSessionId,
                client = client,
                runState = RunState.Running
            )
        )
        // Background commands do not steal the current tab focus: only made active when there is no active tab.
        if (_activeTabId.value == null) _activeTabId.value = id

        if (notify) monitorBackgroundExit(id)

        startKeepaliveService()
        FileLogger.i(TAG, "Background command tab $id: $command")
        return id
    }

    private fun runningTab(id: String): TerminalTab? {
        val t = tab(id) ?: return null
        return if (t.runState is RunState.Running) t else null
    }

    /** Send input to a tab by id and press enter to run it (the AI's entry for sending commands repeatedly). Returns whether the tab was found and is still active. */
    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean {
        val tab = runningTab(id) ?: return false
        val text = if (appendNewline && !input.endsWith("\n")) input + "\n" else input
        writeToSession(tab.session, text)
        return true
    }

    /** Write raw text to a tab by id, without appending a newline. */
    override fun writeToTab(id: String, text: String): Boolean {
        val tab = runningTab(id) ?: return false
        writeToSession(tab.session, text)
        return true
    }

    /** Write raw bytes to a tab by id (control characters, e.g. Ctrl-C=0x03). */
    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean {
        val tab = runningTab(id) ?: return false
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
        return true
    }

    /** Write text to the currently active tab (extra key row: arrow keys/Tab, etc.). */
    fun writeToActive(text: String) {
        activeTab?.let { writeToSession(it.session, text) }
    }

    /** Write raw bytes to the currently active tab (control characters, e.g. Ctrl-C=0x03). */
    fun writeBytesToActive(vararg bytes: Int) {
        val tab = activeTab ?: return
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
    }

    /**
     * Read terminal content by id (the emulator screen buffer's full transcript), for the AI to pull.
     * Returns null when there is no such tab.
     */
    override fun getTabOutput(id: String): String? {
        val tab = tab(id) ?: return null
        return runCatching {
            tab.session.emulator?.screen?.transcriptText?.trimEnd('\n')
        }.getOrNull() ?: ""
    }

    /** List summaries of all tabs (id/title/background flag/run state/command), for the AI to pick a target. */
    override fun listTabs(): List<TabInfo> = _tabs.value.map {
        TabInfo(
            id = it.id,
            title = it.title,
            isBackground = it.isBackground,
            running = it.runState is RunState.Running,
            command = it.command
        )
    }

    /** Switch the current tab. */
    fun activate(id: String) {
        if (_tabs.value.any { it.id == id }) _activeTabId.value = id
    }

    /** Close and destroy a tab (user-initiated close / AI close). Removed from the list and the session killed. */
    override fun closeTab(id: String): Boolean {
        val tab = tab(id) ?: return false
        runCatching { tab.session.finishIfRunning() }
        tab.view = null
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeTabId.value == id) {
            _activeTabId.value = remaining.lastOrNull()?.id
        }
        bumpRevision()

        if (tab.isBackground) maybeStopKeepalive(tab)
        notifyTerminalCount()
        FileLogger.i(TAG, "Closed terminal tab $id")
        return true
    }

    fun closeAllTabs(notify: Boolean = true) {
        _tabs.value.forEach { tab ->
            runCatching { tab.session.finishIfRunning() }
            tab.view = null
        }
        _tabs.value = emptyList()
        _activeTabId.value = null
        bumpRevision()
        if (notify) notifyTerminalCount()
    }

    fun rename(id: String, title: String) {
        tab(id)?.let {
            it.title = title
            bumpRevision()
        }
    }

    private suspend fun ensureContainer() {
        val startedAt = System.currentTimeMillis()
        if (isDevice()) {
            // The device channel has no container concept: readiness = one su probe (ensureInstalled throws if it fails)
            deviceRootEngine.ensureInstalled()
            deviceRootEngine.refreshWorkspaceLinks()
            FileLogger.i(TAG, "Device ROOT ready (took ${System.currentTimeMillis() - startedAt}ms)")
            return
        }
        containerEngine.ensureInstalled()
        if (!containerEngine.isContainerInstalled()) {
            throw IllegalStateException("Container is not installed (missing rootfs/proot)")
        }
        FileLogger.i(TAG, "Container ready (took ${System.currentTimeMillis() - startedAt}ms)")
    }

    private fun nextId(): String = "term-${idCounter.incrementAndGet()}"

    /**
     * Completion fallback monitor: termux session completion relies on the PTY reading EOF, but the proot host process on Android may
     * probabilistically never exit (even after its bash runs exit, proot keeps holding /dev/pts), leaving onFinished
     * untriggered and notify=true tasks without notifications. bash always prints `[command exited: N]` before truly exiting,
     * so that is the reliable completion signal: once seen, wait a short buffer (leaving time for the normal callback), then force-finish if still Running.
     */
    /** Locate the exit marker `[command exited: N]` in the trailing lines of the output text and parse the exit code; returns null when not found.
     *  The marker must appear as its own line (preceded by a newline or at the start of the text), searched backward from the end to avoid PTY command-line echo false positives. */
    private fun extractExitCode(output: String): Int? {
        val tail = output.takeLast(1000)
        val idx = tail.lastIndexOf(EXIT_MARKER_PREFIX)
        if (idx < 0) return null
        if (idx > 0 && tail[idx - 1] != '\n' && tail[idx - 1] != '\r') return null
        var end = idx + EXIT_MARKER_PREFIX.length
        if (end >= tail.length || !tail[end].isDigit()) return null
        var code = 0
        while (end < tail.length && tail[end].isDigit()) {
            code = code * 10 + (tail[end] - '0')
            end++
        }
        return if (end < tail.length && tail[end] == ']') code else null
    }

    private fun monitorBackgroundExit(tabId: String) {
        monitorScope.launch {
            var seenMarker = false
            var lastOutputLen = -1
            while (true) {
                val tab = tab(tabId) ?: return@launch
                if (tab.runState !is RunState.Running) return@launch
                val output = getTabOutput(tabId) ?: return@launch
                if (!seenMarker) {
                    // Skip scanning when output has not grown: only compare lengths, avoiding a full marker scan each round.
                    if (output.length != lastOutputLen) {
                        lastOutputLen = output.length
                        if (extractExitCode(output) != null) seenMarker = true
                    }
                } else {
                    delay(EXIT_MARKER_GRACE_MS)
                    // Re-check after the buffer: if the normal onFinished callback already fired, the state is no longer Running and we exit here.
                    val current = tab(tabId) ?: return@launch
                    if (current.runState is RunState.Running) {
                        val exitCode = extractExitCode(getTabOutput(tabId) ?: "") ?: 0
                        current.runState = RunState.Finished(exitCode)
                        bumpRevision()
                        FileLogger.i(TAG, "Fallback: tab $tabId exit marker detected, force-finishing exit=$exitCode")
                        if (current.isBackground) maybeStopKeepalive(current)
                        if (current.notifyOnExit && !current.finishedNotified) {
                            current.finishedNotified = true
                            _tabFinishedEvents.tryEmit(
                                TabFinishedEvent(
                                    current.id, current.title, current.command, exitCode, current.sourceSessionId,
                                    tailOutput = getTabOutput(current.id)?.takeTailLines(TAIL_LINES)
                                )
                            )
                        }
                    }
                    return@launch
                }
                delay(EXIT_MARKER_POLL_MS)
            }
        }
    }

    private fun addTab(tab: TerminalTab) {
        _tabs.value = _tabs.value + tab
        bumpRevision()
        notifyTerminalCount()
    }

    private fun notifyTerminalCount() {
        TerminalKeepaliveService.setTerminalCount(appContext, _tabs.value.size)
    }

    private fun writeToSession(session: TerminalSession, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    /**
     * Build a PTY session (container proot / device su) and wire up the output/finish callbacks.
     *
     * The client's viewProvider/onFinished look up [_tabs] keyed by session: sessions and tabs map one-to-one,
     * so there is no need to inject the tab reference into the client up front (avoiding the chicken-and-egg of building the client before the tab exists).
     */
    private fun buildSession(shellCommand: String): Pair<TerminalSession, AppTerminalSessionClient> {
        val workspace = workspaceRepository.currentPath()
        // Executable / argv / env / cwd branch by channel; argv[0] must match the executable
        // (Termux TerminalSession's cmd is only used for execvp lookup; the real argv is formed verbatim from args).
        val executable: String
        val argv: Array<String>
        val env: Array<String>
        val cwd: String
        if (isDevice()) {
            executable = resolveSuExecutable()
            argv = arrayOf(executable, "-c", shellCommand)
            // TerminalSession's env array does not inherit the parent process, so merge explicitly (same semantics as proot's ptyEnvArray);
            // su resets HOME; shellCommand already exports the fix, this HOME is just a fallback.
            env = (System.getenv() + mapOf(
                "HOME" to pathHomeResolver.home(),
                "TERM" to "xterm-256color"
            )).map { "${it.key}=${it.value}" }.toTypedArray()
            cwd = if (java.io.File(workspace).isDirectory) workspace else appContext.filesDir.absolutePath
            FileLogger.i(TAG, "PTY start (device): workspace=$workspace su=$executable")
        } else {
            val invocation = containerEngine.buildProotInvocation(shellCommand, workspace)
            executable = invocation.executable
            argv = invocation.argv.toTypedArray()
            env = invocation.ptyEnvArray
            cwd = appContext.filesDir.absolutePath
            FileLogger.i(
                TAG,
                "PTY start: workspace=$workspace executable=$executable" +
                    "(exists=${java.io.File(executable).exists()}) argv=${argv.joinToString(" ")}"
            )
            FileLogger.d(TAG, "PTY env keys: ${invocation.env.keys.joinToString(",")}")
        }
        lateinit var session: TerminalSession
        val client = AppTerminalSessionClient(
            context = appContext,
            viewProvider = { _tabs.value.firstOrNull { it.session === session }?.view },
            onFinished = { finished ->
                _tabs.value.firstOrNull { it.session === finished }?.let { target ->
                    target.runState = RunState.Finished(finished.exitStatus)
                    bumpRevision()
                    FileLogger.i(TAG, "Terminal tab ${target.id} session finished exit=${finished.exitStatus}")
                    if (target.isBackground) maybeStopKeepalive(target)
                    if (target.notifyOnExit && !target.finishedNotified) {
                        target.finishedNotified = true
                        _tabFinishedEvents.tryEmit(
                            TabFinishedEvent(
                                target.id, target.title, target.command, finished.exitStatus, target.sourceSessionId,
                                tailOutput = getTabOutput(target.id)?.takeTailLines(TAIL_LINES)
                            )
                        )
                    }
                }
            }
        )
        session = TerminalSession(executable, cwd, argv, env, TRANSCRIPT_ROWS, client)
        session.updateSize(DEFAULT_COLUMNS, DEFAULT_ROWS)
        FileLogger.i(TAG, "PTY session created: pid=${session.pid} running=${session.isRunning}")
        return session to client
    }

    /** Resolve the su executable path: probe common layouts, fall back to bare "su" (relying on execvp PATH search) when not found. */
    private fun resolveSuExecutable(): String =
        SU_CANDIDATES.firstOrNull { java.io.File(it).exists() } ?: "su"

    private fun startKeepaliveService() {
        TerminalKeepaliveService.startSession(appContext)
        FileLogger.i(TAG, "Background keepalive service started")
    }

    private fun stopKeepaliveService() {
        TerminalKeepaliveService.stopSession(appContext)
        FileLogger.i(TAG, "Background keepalive service stopped")
    }

    private fun maybeStopKeepalive(closingTab: TerminalTab) {
        if (closingTab.isBackground && _tabs.value.none { it.isBackground && it.runState is RunState.Running }) {
            stopKeepaliveService()
        }
    }
}
