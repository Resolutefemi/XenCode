package com.xencode.feature.terminal.domain

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xencode.feature.settings.data.repository.KeepaliveSettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Keepalive fallback worker: periodically checks whether the persistent keepalive service is alive, and restarts it if killed.
 *
 * START_STICKY relies on the system rebuilding the process after reclaim, which is neither timely nor guaranteed; WorkManager is scheduled by the system and more reliable.
 * It is only scheduled when the user turns on the background keepalive toggle (see [com.xencode.AIEditorApp]) and the task is cancelled when turned off,
 * so the toggle is re-checked here once more to avoid a mistaken restart after the user turned it off post-scheduling.
 */
@HiltWorker
class KeepaliveWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val keepaliveSettings: KeepaliveSettingsRepository
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result {
        if (!keepaliveSettings.isEnabled()) return Result.success()
        if (!TerminalKeepaliveService.isRunning(applicationContext)) {
            TerminalKeepaliveService.enablePersistent(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "keepalive-check"
        /** Minimum period allowed by WorkManager. */
        private const val PERIOD_MINUTES = 15L

        /** Schedule the periodic fallback task (idempotent). Called when the keepalive toggle is on. */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<KeepaliveWorker>(PERIOD_MINUTES, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** Cancel the periodic fallback task. Called when the keepalive toggle is off. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}