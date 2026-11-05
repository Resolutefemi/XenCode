package com.xencode

import android.app.ActivityManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.work.Configuration
import androidx.hilt.work.HiltWorkerFactory
import com.xencode.core.net.AppProxy
import com.xencode.core.util.AILogger
import com.xencode.core.util.FileLogger
import net.schmizz.sshj.common.SecurityUtils
import com.xencode.feature.agent.domain.container.ContainerInstaller
import com.xencode.feature.agent.domain.mcp.McpManager
import com.xencode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.xencode.feature.settings.data.repository.LanguageSettingsRepository
import com.xencode.feature.settings.data.repository.LogSettingsRepository
import com.xencode.feature.settings.data.remote.ModelMetadataService
import com.xencode.feature.terminal.domain.KeepaliveWorker
import com.xencode.feature.terminal.domain.TerminalKeepaliveService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AIEditorApp : Application(), Configuration.Provider {

    companion object {
        const val TAG = "AIEditorApp"
        const val LANG_PREFS = "language_prefs_sync"
        const val LANG_KEY = "language_tag"
        /** The crash stack travels via Intent extras; exceeding the Binder limit throws, so it must be truncated. */
        const val MAX_CRASH_STACK_CHARS = 50_000
        private const val CRASH_PREFS = "crash_state"
        private const val KEY_CRASH_UI_SHOWING = "crash_ui_showing"
        private const val KEY_LAST_CRASH_TIME = "last_crash_time"
        private const val KEY_CRASH_COUNT = "crash_count"
        /** Consecutive crashes within 10 seconds count as a crash burst. */
        private const val CRASH_RESET_WINDOW_MS = 10_000L
        /** A burst of 3 crashes trips the hard breaker and exits, preventing infinite loops. */
        private const val MAX_CONSECUTIVE_CRASHES = 3

        /** Current navigation route (written by MainActivity), attached to crash reports to locate the crashing screen. */
        @Volatile
        var currentRoute: String? = null

        /** Current workspace mode (local PRoot / remote SSH), attached to crash reports. */
        @Volatile
        var currentWorkspaceMode: String? = null

        /** Resets crash state (called when the main process runs healthy or the user restarts from the error page). */
        fun resetCrashState(context: android.content.Context) {
            context.getSharedPreferences(CRASH_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_CRASH_UI_SHOWING)
                .remove(KEY_CRASH_COUNT)
                .remove(KEY_LAST_CRASH_TIME)
                .apply()
        }

        /** Legacy alias kept for backward compatibility with external callers. */
        fun resetCrashUiFlag(context: android.content.Context) {
            resetCrashState(context)
        }

        /** Gets the current process name, compatibly. */
        fun getProcessNameCompat(context: android.content.Context): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return Application.getProcessName()
            }
            return runCatching {
                val cmdline = java.io.File("/proc/self/cmdline")
                if (cmdline.exists()) {
                    cmdline.readText().trim().trim { it <= ' ' || it == '\u0000' }
                } else null
            }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching {
                    val activityThread = Class.forName("android.app.ActivityThread")
                    val method = activityThread.getMethod("currentProcessName")
                    method.invoke(null) as? String
                }.getOrNull()
                ?: context.packageName
        }

        /** Returns whether this process is the child process dedicated to rendering the crash error page. */
        fun isCrashProcess(context: android.content.Context): Boolean =
            getProcessNameCompat(context).endsWith(":crash")
    }

    override fun attachBaseContext(base: android.content.Context) {
        // Earliest entry point: have logging and crash persistence ready before any Hilt injection / business init.
        // A crash early in startup (e.g. the Hilt injection chain instantiating @Singleton utilities) that happens
        // before FileLogger initializes would leave no trace, so logging and the global crash handler go first in attachBaseContext.
        FileLogger.init(base)
        AILogger.init(base)
        installCrashHandler()
        // Global proxy entry point: must be set before any Hilt injection / OkHttpClient construction,
        // so every app-side HTTP path (chat, MCP, update checks, etc.) can use the global/provider proxy as needed.
        // Use base rather than the local context defined below: at attachBaseContext time base is the valid Context.
        AppProxy.applyGlobal(base)
        val tag = base.getSharedPreferences(LANG_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(LANG_KEY, null)
        val locale = when {
            tag.isNullOrBlank() -> java.util.Locale.ENGLISH
            com.xencode.core.util.LanguageRegistry.isSupported(tag) -> java.util.Locale.forLanguageTag(tag)
            else -> java.util.Locale.ENGLISH
        }
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    /** Hilt field injection: available after the super call in [onCreate]. */
    @Inject
    lateinit var logSettings: LogSettingsRepository

    /** Persistence for the background keep-alive switch. */
    @Inject
    lateinit var keepaliveSettings: KeepaliveSettingsRepository

    /** WorkManager worker factory: lets @HiltWorker (KeepaliveWorker) get its dependencies injected. */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** MCP lifecycle manager: connects configured remote servers at startup. */
    @Inject
    lateinit var mcpManager: McpManager

    /** MCP config repository: watches for external direct edits to mcp.json at startup; changes refresh the list within seconds and trigger reconnection. */
    @Inject
    lateinit var mcpConfigRepository: com.xencode.feature.agent.domain.mcp.McpConfigRepository

    /** Tool authorization rules repository: watches for external direct edits to permissions.json at startup; changes refresh the rules within seconds. */
    @Inject
    lateinit var permissionRulesRepository: com.xencode.feature.agent.domain.permission.PermissionRulesRepository

    /** Skill config repository: watches the skills directory and skills.json for external changes at startup; the skill list refreshes within seconds. */
    @Inject
    lateinit var skillConfigRepository: com.xencode.feature.agent.domain.skill.SkillConfigRepository

    /** One-shot migrator for legacy Room git credentials: at startup writes the old table into the git-credentials file, then drops the table (the file is now the source of truth). */
    @Inject
    lateinit var legacyCredentialMigrator: com.xencode.feature.credentials.data.LegacyCredentialMigrator

    /** Sensitive-field encryption migrator: at startup re-encrypts historically plaintext API key/password/private-key files in place (idempotent). */
    @Inject
    lateinit var secretEncryptionMigrator: com.xencode.feature.settings.data.SecretEncryptionMigrator

    /** Git credential repository: at startup migrates the legacy plaintext credential file to the encoded format. */
    @Inject
    lateinit var fileCredentialRepository: com.xencode.feature.credentials.data.repository.FileCredentialRepository

    /** Unified dialog bridge for missing git credentials across all ends: listens for signed-out requests sent over file IPC by the in-container credential helper,
     *  exposes a StateFlow for a global dialog to fill in and feed back to git. Must be started on the main thread (FileObserver binds the main Looper). */
    @Inject
    lateinit var credentialRequestBridge: com.xencode.feature.credentials.data.CredentialRequestBridge

    /** Execution mode repository (local PRoot / remote SSH). */
    @Inject
    lateinit var executionModeRepository: com.xencode.feature.settings.data.repository.ExecutionModeRepository

    /** App language preference repository: persists the user-selected language for attachBaseContext to read synchronously. */
    @Inject
    lateinit var languageSettings: LanguageSettingsRepository

    /** Synchronous execution mode cache: reads the first frame from DataStore at startup and feeds it into DI. */
    @Inject
    lateinit var executionModeHolder: com.xencode.feature.settings.data.repository.ExecutionModeHolder

    /** Remote SSH connection manager: in remote mode, connects using the config at startup. */
    @Inject
    lateinit var remoteSshConnection: com.xencode.feature.agent.domain.container.RemoteSshConnection

    /** Resolver for the active profile's connection config: drives reconnection when the connection/profile/mode changes. */
    @Inject
    lateinit var activeRemoteConnectionResolver: com.xencode.feature.agent.domain.container.ActiveRemoteConnectionResolver

    /** Workspace repository: reloads the workspace after a successful SSH reconnection. */
    @Inject
    lateinit var workspaceRepository: com.xencode.feature.workspace.data.repository.WorkspaceRepository

    /** Connection & sync repository: injected so the @Singleton is created early; it internally follows the current workspace, connecting/disconnecting mounts automatically. */
    @Inject
    lateinit var remoteRepository: com.xencode.feature.workspace.domain.repository.RemoteRepository

    /** Model metadata service: pulls the model catalog from this repository asynchronously at startup (12h cache, silent on failure, falls back to built-in data). */
    @Inject
    lateinit var modelMetadataService: ModelMetadataService

    /** Provider-level proxy registry: builds the provider->proxy index at startup for AppProxy to dispatch by target host. */
    @Inject
    lateinit var providerProxyRegistry: com.xencode.feature.settings.data.repository.ProviderProxyRegistry

    /** Long-lived scope: continuously syncs the persisted log level into FileLogger. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        if (isCrashProcess(this)) {
            // The crash error page runs in its own :crash process.
            // Deliberately skip super.onCreate() (avoiding Hilt's @Inject field injection and implicit business class loading),
            // and never start main-process business coroutines, database access, or persistent services, giving the error page a clean, lightweight rendering environment.
            return
        }
        super.onCreate()
        logDeviceInfo()
        // Attach the provider-level proxy registry to AppProxy (applyGlobal already ran in attachBaseContext);
        // from here on, provider-specific proxies are dispatched by target host; without a provider config it falls back to the global proxy.
        AppProxy.registerProviderProxyRegistry(providerProxyRegistry)
        // Registering the full BouncyCastle goes to a background IO coroutine, avoiding loading a pile of crypto classes on the main thread and blocking first paint;
        // the remote SSH connection runs in appScope, and registration only needs to finish before first use.
        appScope.launch(Dispatchers.IO) {
            registerBouncyCastle()
        }
        createNotificationChannels()
        // Start the credential request listener: cred-req-* written by the in-container credential helper -> global dialog fills it in -> fed back to git to resume.
        credentialRequestBridge.start()
        // -- Non-critical background tasks: deferred until after first paint --
        // On a cold start, JIT-compiling the giant Composables (AIChatPanel etc.) allocates ~39MB and triggers a GC storm that blocks the main thread.
        // These file extraction/migration/network refresh tasks allocate many temporary objects on background threads; running them alongside JIT compounds GC pressure.
        // Delaying 500ms lets the JIT compilation window pass first, keeping first paint free of GC interference. The tasks are all idempotent, so a 500ms delay is harmless.
        appScope.launch {
            kotlinx.coroutines.delay(500)
            ContainerInstaller.extractDocs(this@AIEditorApp)
        }
        appScope.launch {
            kotlinx.coroutines.delay(500)
            ContainerInstaller.extractPrompts(this@AIEditorApp)
        }
        appScope.launch {
            kotlinx.coroutines.delay(500)
            ContainerInstaller.extractScripts(this@AIEditorApp)
        }
        appScope.launch {
            kotlinx.coroutines.delay(500)
            ContainerInstaller.extractAgents(this@AIEditorApp)
        }
        appScope.launch {
            kotlinx.coroutines.delay(500)
            legacyCredentialMigrator.migrateIfNeeded()
        }
        appScope.launch {
            kotlinx.coroutines.delay(500)
            secretEncryptionMigrator.migrateIfNeeded()
        }
        appScope.launch {
            kotlinx.coroutines.delay(500)
            fileCredentialRepository.migrateToEncoded()
        }
        appScope.launch {
            kotlinx.coroutines.delay(800)
            modelMetadataService.refreshFromNetworkIfStale()
        }
        appScope.launch {
            kotlinx.coroutines.delay(800)
            com.xencode.feature.settings.data.local.ProviderPresetLibrary.refreshFromNetworkIfStale(this@AIEditorApp)
        }
        // Load the persisted level at startup and apply settings-screen changes in real time (the single sync point).
        appScope.launch {
            logSettings.levelFlow.collectLatest { FileLogger.setMinLevel(it) }
        }
        // At startup, synchronously read the first-frame execution mode from DataStore into ExecutionModeHolder for DI @Provides to read synchronously.
        // Reads the first-frame mode asynchronously and writes it into ExecutionModeHolder. The delegating layer (DelegatingCommandEngine/DelegatingFileAccess)
        // forwards per method call based on holder.currentMode(), independent of injection timing, so a few milliseconds' delay writing the holder is fine —
        // the first command/file operation always happens after the UI starts, by which time the holder is long ready.
        // The SSH connection goes to the background; failure does not block the UI (if it failed, the first command triggers an ensureInstalled retry).
        appScope.launch {
            val mode = executionModeRepository.executionModeFlow.first()
            executionModeHolder.setMode(mode)
        }
        // SSH connection supervisor runs persistently (idles with an empty config): periodic liveness checks, automatic reconnect on drop; on reconnect it reloads the workspace and syncs docs.
        appScope.launch {
            remoteSshConnection.startSupervisor(appScope) {
                runCatching { workspaceRepository.initialize() }
                    .onFailure { FileLogger.w(TAG, "Failed to reload workspace after SSH reconnection", it) }
                syncDocsToRemote()
                releaseBuiltinAssetsToRemote()
            }
        }
        // Reconnect whenever the active profile's connection config changes (edit connection / edit profile / switch profile / switch mode), so connection edits take effect immediately.
        appScope.launch {
            combine(
                activeRemoteConnectionResolver.activeConfigFlow,
                executionModeHolder.mode
            ) { config, mode -> config to mode }.collect { (config, mode) ->
                if (mode != com.xencode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH) return@collect
                if (config == null || config == remoteSshConnection.config) return@collect
                runCatching {
                    remoteSshConnection.connect(config)
                    // After connecting, sync built-in docs to the remote ~/.xencode/docs/ for the AI to consult.
                    syncDocsToRemote()
                    releaseBuiltinAssetsToRemote()
                }.onFailure { FileLogger.e(TAG, "SSH connection failed; will retry on the first command", it) }
            }
        }
        // RemoteRepository handles "follow the current workspace" for connections and sync internally by watching workspace changes (ready as soon as injected at startup):
        // when a workspace becomes ready or switches, it automatically disconnects other workspaces' mounts and connects the mounts in the current workspace marked "auto-connect on app start".
        // The sole reactor for the background keep-alive persistent notification: watches the switch and starts/stops TerminalKeepaliveService's persistent mode.
        // Covers both live toggling in settings and cold-start restore. Sends disable only on an on->off transition,
        // avoiding spuriously starting a Service that was never on just to turn it off.
        appScope.launch {
            var last: Boolean? = null
            keepaliveSettings.enabledFlow.distinctUntilChanged().collect { enabled ->
                if (enabled) {
                    TerminalKeepaliveService.enablePersistent(this@AIEditorApp)
                    // WorkManager fallback: periodically checks the service is alive and revives it after a kill.
                    KeepaliveWorker.schedule(this@AIEditorApp)
                } else if (last == true) {
                    TerminalKeepaliveService.disablePersistent(this@AIEditorApp)
                    KeepaliveWorker.cancel(this@AIEditorApp)
                }
                last = enabled
            }
        }
        // Connect configured MCP servers and register their tools into ToolRegistry (own internal scope; failure does not affect startup).
        mcpManager.start()
        // Permission rules are subscribed persistently at app startup (AI evaluation must always read the latest rules); MCP config and the skill list
        // are instead subscribed on demand by their consumers (McpManager / the settings screen), so nothing needs to start here.
        appScope.launch { permissionRulesRepository.startWatching() }
        // Continuously sync the workspace mode cache; attached to crash reports
        appScope.launch {
            executionModeRepository.executionModeFlow.collect { mode ->
                currentWorkspaceMode = mode.name
            }
        }
        // 10 seconds after the main process starts healthy, auto-reset the consecutive-crash counter and the error-page flag, restoring a healthy state
        appScope.launch {
            kotlinx.coroutines.delay(10_000)
            resetCrashState(this@AIEditorApp)
        }
        // Language switching is managed uniformly by MainActivity's attachBaseContext + recreate().
        // MainActivity extends ComponentActivity (not AppCompatActivity),
        // so AppCompatDelegate.setApplicationLocales's automatic recreate does not take effect,
        // and setting the locale in both places would race and occasionally scramble the language.
    }

    /** Log device/version info: when a user reports "the container will not start on some OS version", the log needs the system and ABI context first. */
    private fun logDeviceInfo() {
        runCatching {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            FileLogger.i(
                TAG,
                "Device info: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}" +
                    "(API ${Build.VERSION.SDK_INT}), ABI=${Build.SUPPORTED_ABIS.joinToString(",")}, " +
                    "targetSdk=${applicationInfo.targetSdkVersion}, nativeLibraryDir=${applicationInfo.nativeLibraryDir}, " +
                    "version=$versionName"
            )
        }.onFailure { FileLogger.w(TAG, "Failed to log device info", it) }
    }

    /** HiltWorkerFactory: @HiltWorker workers must be created through this factory to get Repository injection. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * Reads all built-in docs under assets/docs (including category subdirectories like guide/ and advanced/) and syncs them to the remote ~/.xencode/docs/ via SSH exec.
     * In remote mode, when the AI consults the settings docs under ~/.xencode/docs/, these files must exist on the remote server.
     * Called after a successful connect and reconnect, keeping remote docs updated as the app upgrades. Failures are only logged and never block the flow.
     */
    private suspend fun syncDocsToRemote() {
        runCatching {
            val docs = linkedMapOf<String, String>()
            collectAssetDocs("docs", "", docs)
            remoteSshConnection.uploadDocs(docs)
        }.onFailure { FileLogger.w(TAG, "Failed to sync built-in docs to remote", it) }
    }

    /**
     * Releases the built-in sub-agent definition (Explore) and panel example scripts to the remote `~/.xencode/agents` and `~/.xencode/scripts`.
     * In remote mode the remote side is authoritative for global agents/scripts; the built-in defaults must exist there to work out of the box — only fills in what is missing, never overwrites user changes.
     */
    private suspend fun releaseBuiltinAssetsToRemote() {
        remoteSshConnection.uploadBuiltinFiles("agents", collectAssetBytes("agents"))
        remoteSshConnection.uploadBuiltinFiles("scripts", collectAssetBytes("xencode/scripts"), executable = true)
    }

    /** Collects the flat files in an assets directory (name -> bytes). Directory entries are skipped. */
    private fun collectAssetBytes(assetDir: String): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        assets.list(assetDir)?.forEach { entry ->
            runCatching { assets.open("$assetDir/$entry").use { out[entry] = it.readBytes() } }
                .onFailure { FileLogger.w(TAG, "Failed to read built-in asset: $assetDir/$entry", it) }
        }
        return out
    }

    /** Recursively collects asset docs; the key is the path relative to docs/ (e.g. guide/terminal.md). */
    private fun collectAssetDocs(assetDir: String, relativePrefix: String, out: MutableMap<String, String>) {
        val entries = assets.list(assetDir) ?: return
        for (entry in entries) {
            val assetPath = "$assetDir/$entry"
            val relativePath = if (relativePrefix.isEmpty()) entry else "$relativePrefix/$entry"
            try {
                out[relativePath] = assets.open(assetPath).bufferedReader().use { it.readText() }
            } catch (e: java.io.IOException) {
                // Directory entry: assets.open throws IOException for directories, recurse into it
                collectAssetDocs(assetPath, relativePath, out)
            }
        }
    }

    /** Registers the full BouncyCastle to replace Android's trimmed-down version.
     *  sshj 0.38.0 uses X25519 for key exchange; Android's bundled BC provider lacks the X25519 algorithm,
     *  so the trimmed version must be removed first, then the full bcprov-jdk18on (an sshj transitive dependency)
     *  registered, and sshj told to use it. Must complete before any sshj call. */
    private fun registerBouncyCastle() {
        // Remove Android's trimmed-down BC first, then register the full bcprov-jdk18on.
        // Use addProvider rather than insertProviderAt(..., 1): BC just needs to exist in the Provider list so
        // sshj can find it by name via SecurityUtils.setSecurityProvider("BC"); top priority is unnecessary.
        // If it were moved to position 1, it would hijack the KeyStore lookup when OkHttp/Conscrypt initializes
        // the default SSLContext — BC registers the BKS type but ships no matching default truststore, throwing
        // KeyStoreException: BKS not found (manifesting as crashes on HTTPS requests like update checks). Leaving it last lets the system's own providers keep handling TLS.
        java.security.Security.removeProvider("BC")
        java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        SecurityUtils.setSecurityProvider("BC")
    }

    /**
     * Catches unhandled exceptions: persists the crash, launches the full-screen error page (showing crash details,
     * supporting copy), then kills the process so the system restarts and restores the error page.
     *
     * The process must be killed: after a main-thread crash its Looper is dead; without killing, the error page
     * window appears but onCreate never runs (blank screen) and is eventually killed as an ANR. Once killed, AMS
     * detects the process died mid-Activity-creation, automatically restarts it and restores CrashActivity —
     * with a healthy main thread in the new process, the error page renders properly.
     *
     * Recursion guard: if the error page itself crashes, the persisted flag is still set, so hand it straight
     * back to the system default handler to avoid infinite restarts.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val isCrash = isCrashProcess(this)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val processName = getProcessNameCompat(this)
            FileLogger.e("CRASH", "Uncaught exception on thread ${thread.name} (process=$processName)", throwable)
            // Flush buffered logs synchronously before the crash so the last chunk (including this error line) is not lost in memory.
            FileLogger.flushSync()

            // 1. If the :crash process itself throws: hand back to the system default handler and exit for good — never launch the error page again
            if (isCrash) {
                FileLogger.e(TAG, "Exception in the crash error page process; killing the process to avoid recursion")
                previous?.uncaughtException(thread, throwable)
                Process.killProcess(Process.myPid())
                return@setDefaultUncaughtExceptionHandler
            }

            // 2. Main process crash: check crash frequency and state to prevent an infinite relaunch loop
            val prefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
            val isUiShowing = prefs.getBoolean(KEY_CRASH_UI_SHOWING, false)
            val lastCrashTime = prefs.getLong(KEY_LAST_CRASH_TIME, 0L)
            val now = System.currentTimeMillis()
            val recentCrashCount = if (now - lastCrashTime < CRASH_RESET_WINDOW_MS) {
                prefs.getInt(KEY_CRASH_COUNT, 0) + 1
            } else {
                1
            }
            prefs.edit()
                .putLong(KEY_LAST_CRASH_TIME, now)
                .putInt(KEY_CRASH_COUNT, recentCrashCount)
                .apply()

            // Circuit breaker: a crash while the error page is showing, or a burst of crashes hitting the threshold in a short window
            if (isUiShowing || recentCrashCount >= MAX_CONSECUTIVE_CRASHES) {
                FileLogger.e(TAG, "Crash circuit breaker tripped (isUiShowing=$isUiShowing, count=$recentCrashCount); stopping auto-restart and leaving it to the system")
                previous?.uncaughtException(thread, throwable)
                Process.killProcess(Process.myPid())
                return@setDefaultUncaughtExceptionHandler
            }

            prefs.edit().putBoolean(KEY_CRASH_UI_SHOWING, true).apply()
            try {
                startActivity(
                    Intent(this, CrashActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra(CrashActivity.EXTRA_THREAD_NAME, thread.name)
                        putExtra(CrashActivity.EXTRA_STACK, stackTraceOf(throwable))
                        putExtra(CrashActivity.EXTRA_SCREEN, currentRoute)
                        putExtra(CrashActivity.EXTRA_WORKSPACE_MODE, currentWorkspaceMode)
                        putExtra(CrashActivity.EXTRA_MEMORY, memorySummary())
                    }
                )
            } catch (t: Throwable) {
                // Error page failed to start (e.g. system restrictions): hand back to the default handler so the crash is not swallowed
                FileLogger.e(TAG, "Failed to launch the crash error page", t)
                prefs.edit().remove(KEY_CRASH_UI_SHOWING).apply()
                previous?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            Process.killProcess(Process.myPid())
        }
    }

    /**
     * Memory snapshot of the crashing process, attached to the report. Must be captured in the main process —
     * the error page runs in the separate :crash process, where reading the heap only reflects the reporting
     * process and is useless for diagnosing OOM.
     */
    private fun memorySummary(): String = runCatching {
        val runtime = Runtime.getRuntime()
        val maxMb = runtime.maxMemory() / 1024 / 1024
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val pssMb = runCatching {
            am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()?.totalPss?.toLong()?.div(1024)
        }.getOrNull()
        buildString {
            append("heap ").append(usedMb).append('/').append(maxMb).append("MB")
            if (pssMb != null) append(", PSS ").append(pssMb).append("MB")
            append(", memoryClass ").append(am.memoryClass).append("MB")
            append(", lowRam ").append(am.isLowRamDevice)
        }
    }.getOrDefault("unavailable")

    private fun stackTraceOf(throwable: Throwable): String {
        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))
        val text = sw.toString()
        return if (text.length > MAX_CRASH_STACK_CHARS) {
            text.take(MAX_CRASH_STACK_CHARS) + "\n...[truncated]"
        } else {
            text
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                "terminal_service",
                getString(R.string.notification_channel_terminal_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_terminal_service_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)

            // Agent completion notification: needs a heads-up + sound, unlike terminal_service's silent persistent notification.
            val agentChannel = NotificationChannel(
                "agent_complete",
                getString(R.string.notification_channel_agent_complete),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_agent_complete_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(agentChannel)
        }
    }
}
