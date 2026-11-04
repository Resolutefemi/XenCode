package com.aicode.feature.terminal.domain

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aicode.MainActivity
import com.aicode.core.util.FileLogger
import com.aicode.R
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TerminalKeepaliveService : Service() {
    private val binder = LocalBinder()
    private var sessionCount = 0
    private var terminalCount = 0
    private var userWakeLockHeld = false
    private var userWakeLock: PowerManager.WakeLock? = null

    /** True when the settings toggle keeps the foreground notification even with no sessions. */
    private var persistent = false

    /**
     * Service-level PARTIAL_WAKE_LOCK while sessions are active so CPU stays awake
     * and SSE / network I/O is not frozen in the background. Released when all
     * sessions end and persistent keepalive is off.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Service-level WIFI_MODE_FULL_HIGH_PERF lock so Wi-Fi does not drop into
     * low-power mode in the background. PARTIAL_WAKE_LOCK only keeps the CPU
     * awake; Wi-Fi throttling still stalls long-lived TCP (the usual cause of
     * SSE drops). Browser downloads are unaffected because DownloadManager
     * runs in a system process.
     */
    private var wifiLock: WifiManager.WifiLock? = null

    @Inject
    lateinit var keepaliveSettings: KeepaliveSettingsRepository

    @Inject
    lateinit var localTerminals: TerminalSessionManager

    @Inject
    lateinit var remoteTerminals: RemoteTerminalSessionManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    inner class LocalBinder : Binder() {
        fun getService(): TerminalKeepaliveService = this@TerminalKeepaliveService
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.i(TAG, "Service created")
        // START_STICKY rebuild after process death loses in-memory persistent.
        // Restore from the saved toggle so the service does not immediately stopSelf.
        serviceScope.launch {
            if (keepaliveSettings.isEnabled()) {
                persistent = true
                ensureForeground()
                FileLogger.i(TAG, "Restored persistent keepalive after restart")
            }
        }
    }

    override fun onDestroy() {
        releaseServiceWakeLock()
        releaseUserWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Swiping the app from Recents means the user does not want background work:
        // stop the foreground service and cancel the WorkManager fallback, otherwise
        // START_STICKY / KeepaliveWorker would relaunch and show a notification.
        // The settings toggle is left as-is; MainActivity / AIEditorApp restore it next launch.
        KeepaliveWorker.cancel(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        FileLogger.i(TAG, "Task removed, keepalive stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> {
                sessionCount++
                ensureForeground()
                acquireServiceWakeLock()
                FileLogger.i(TAG, "Session started, count=$sessionCount")
            }
            ACTION_STOP_SESSION -> {
                sessionCount = (sessionCount - 1).coerceAtLeast(0)
                if (sessionCount == 0 && terminalCount == 0 && !persistent) {
                    releaseServiceWakeLock()
                    stopSelf(startId)
                    FileLogger.i(TAG, "All sessions ended, stopping service")
                } else {
                    ensureForeground()
                    if (sessionCount == 0 && terminalCount == 0) releaseServiceWakeLock()
                    FileLogger.i(TAG, "Session ended, count=$sessionCount, persistent=$persistent")
                }
            }
            ACTION_ENABLE_PERSISTENT -> {
                persistent = true
                ensureForeground()
                acquireServiceWakeLock()
                FileLogger.i(TAG, "Persistent keepalive enabled")
            }
            ACTION_DISABLE_PERSISTENT -> {
                persistent = false
                if (sessionCount == 0 && terminalCount == 0) {
                    releaseServiceWakeLock()
                    releaseUserWakeLock()
                    stopSelf(startId)
                    FileLogger.i(TAG, "Persistent keepalive disabled, no sessions, stopping service")
                } else {
                    ensureForeground()
                    FileLogger.i(TAG, "Persistent keepalive disabled, sessions still running count=$sessionCount")
                }
            }
            ACTION_SET_TERMINAL_COUNT -> {
                terminalCount = runCatching { localTerminals.tabs.value.size }.getOrDefault(0) +
                    runCatching { remoteTerminals.tabs.value.size }.getOrDefault(0)
                if (terminalCount > 0) {
                    ensureForeground()
                    acquireServiceWakeLock()
                    FileLogger.i(TAG, "Terminal count=$terminalCount")
                } else if (sessionCount == 0 && !persistent) {
                    releaseUserWakeLock()
                    releaseServiceWakeLock()
                    stopSelf(startId)
                    FileLogger.i(TAG, "No terminals or sessions, stopping service")
                } else {
                    if (terminalCount == 0) releaseUserWakeLock()
                    ensureForeground()
                    if (sessionCount == 0 && terminalCount == 0) releaseServiceWakeLock()
                    FileLogger.i(TAG, "Terminals closed, sessionCount=$sessionCount, persistent=$persistent")
                }
            }
            ACTION_EXIT -> {
                runCatching { localTerminals.closeAllTabs(notify = false) }
                runCatching { remoteTerminals.closeAllTabs(notify = false) }
                terminalCount = 0
                releaseUserWakeLock()
                if (sessionCount == 0 && !persistent) {
                    releaseServiceWakeLock()
                    stopSelf(startId)
                    FileLogger.i(TAG, "Exit action: all terminals closed, stopping service")
                } else {
                    ensureForeground()
                    FileLogger.i(TAG, "Exit action: terminals closed, sessionCount=$sessionCount")
                }
            }
            ACTION_TOGGLE_WAKELOCK -> {
                if (userWakeLockHeld) releaseUserWakeLock() else acquireUserWakeLock()
                ensureForeground()
                FileLogger.i(TAG, "User wakelock held=$userWakeLockHeld")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Enter foreground and refresh the notification copy for persistent keepalive /
     * background sessions / open terminals.
     *
     * startForeground on Android 12+ may throw ForegroundServiceStartNotAllowedException
     * when started from the background. Catch and log only: the notification may fail
     * this time, but the process must not crash (the settings toggle stays on and
     * [com.aicode.MainActivity] restores it next time the app is in the foreground).
     */
    private fun ensureForeground() {
        val text = when {
            terminalCount > 0 ->
                resources.getQuantityString(R.plurals.notification_terminal_sessions, terminalCount, terminalCount)
            sessionCount > 0 && persistent ->
                getString(R.string.notification_keepalive_persistent_sessions, sessionCount)
            sessionCount > 0 ->
                getString(R.string.notification_keepalive_running_sessions, sessionCount)
            else -> getString(R.string.notification_keepalive_enabled)
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
        if (terminalCount > 0) {
            builder.addAction(
                0,
                getString(R.string.notification_action_exit),
                serviceActionPendingIntent(ACTION_EXIT, 1)
            )
            builder.addAction(
                0,
                getString(
                    if (userWakeLockHeld) R.string.notification_action_release_wakelock
                    else R.string.notification_action_acquire_wakelock
                ),
                serviceActionPendingIntent(ACTION_TOGGLE_WAKELOCK, 2)
            )
        }
        val notification = builder.build()

        runCatching { startForeground(NOTIFICATION_ID, notification) }
            .onFailure { FileLogger.e(TAG, "startForeground failed", it) }
    }

    /**
     * Acquire service-level PARTIAL_WAKE_LOCK + WIFI_MODE_FULL_HIGH_PERF:
     * WakeLock keeps the CPU awake; WifiLock keeps Wi-Fi in high-perf mode.
     * Both are required: CPU awake with Wi-Fi asleep still drops SSE.
     * Held only while sessions or terminals are active.
     */
    private fun acquireServiceWakeLock() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiCode:KeepaliveService").also {
                    runCatching { it.acquire(WAKELOCK_TIMEOUT_MS) }
                        .onFailure { FileLogger.e(TAG, "acquire service wakeLock failed", it) }
                }
            }
        }
        if (wifiLock?.isHeld != true) {
            val wm = getSystemService(WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AiCode:StreamNetwork").also {
                    runCatching { it.acquire() }
                        .onFailure { FileLogger.e(TAG, "acquire wifiLock failed", it) }
                }
            }
        }
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, TerminalKeepaliveService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun acquireUserWakeLock() {
        if (userWakeLock?.isHeld == true) {
            userWakeLockHeld = true
            return
        }
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        userWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiCode:UserWakeLock").also {
            it.setReferenceCounted(false)
            runCatching { it.acquire() }
                .onFailure { FileLogger.e(TAG, "acquire user wakeLock failed", it) }
        }
        userWakeLockHeld = userWakeLock?.isHeld == true
    }

    private fun releaseUserWakeLock() {
        userWakeLock?.takeIf { it.isHeld }?.let { lock ->
            runCatching { lock.release() }
                .onFailure { FileLogger.e(TAG, "release user wakeLock failed", it) }
        }
        userWakeLock = null
        userWakeLockHeld = false
    }

    /** Release service-level WakeLock and WifiLock. */
    private fun releaseServiceWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.let { lock ->
            runCatching { lock.release() }
                .onFailure { FileLogger.e(TAG, "release service wakeLock failed", it) }
        }
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.let { lock ->
            runCatching { lock.release() }
                .onFailure { FileLogger.e(TAG, "release wifiLock failed", it) }
        }
        wifiLock = null
    }

    companion object {
        private const val TAG = "TerminalKeepaliveService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "terminal_service"
        /** Service WakeLock timeout: AI streaming can run a long time, so allow 60 minutes. */
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L
        const val ACTION_START_SESSION = "com.aicode.action.START_SESSION"
        const val ACTION_STOP_SESSION = "com.aicode.action.STOP_SESSION"
        const val ACTION_ENABLE_PERSISTENT = "com.aicode.action.ENABLE_PERSISTENT"
        const val ACTION_DISABLE_PERSISTENT = "com.aicode.action.DISABLE_PERSISTENT"
        const val ACTION_SET_TERMINAL_COUNT = "com.aicode.action.SET_TERMINAL_COUNT"
        const val ACTION_EXIT = "com.aicode.action.EXIT"
        const val ACTION_TOGGLE_WAKELOCK = "com.aicode.action.TOGGLE_WAKELOCK"

        /**
         * Register an active session and start the foreground service (AI streaming / background terminals).
         * Uses startForegroundService so Android 8+ can start it when returning from the background.
         */
        fun startSession(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_START_SESSION
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** End an active session. Stops the service when the count hits zero and persistent keepalive is off. */
        fun stopSession(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_STOP_SESSION
            }
            runCatching { context.startService(intent) }
                .onFailure { FileLogger.e(TAG, "stopSession startService failed", it) }
        }

        /** Enable persistent keepalive (idempotent). Uses startForegroundService so it can start in the background. */
        fun enablePersistent(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_ENABLE_PERSISTENT
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Disable persistent keepalive (idempotent). Only call if it was previously enabled. */
        fun disablePersistent(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_DISABLE_PERSISTENT
            }
            runCatching { context.startService(intent) }
                .onFailure { FileLogger.e(TAG, "disablePersistent startService failed", it) }
        }

        /** Refresh the foreground notification from the current open terminal tabs. */
        fun setTerminalCount(context: Context, count: Int) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_SET_TERMINAL_COUNT
            }
            if (count > 0) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                runCatching { context.startService(intent) }
                    .onFailure { FileLogger.e(TAG, "setTerminalCount startService failed", it) }
            }
        }

        /** True if the keepalive service is currently running. */
        @Suppress("DEPRECATION")
        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val serviceName = TerminalKeepaliveService::class.java.name
            return am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.packageName == context.packageName && it.service.className == serviceName }
        }
    }
}
