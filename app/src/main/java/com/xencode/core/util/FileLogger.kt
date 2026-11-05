package com.xencode.core.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Log levels, from lowest to highest. As a threshold, [NONE] turns off all output (no
 * level is >= NONE).
 *
 * Order is severity: logs below the [FileLogger.minLevel] threshold (logcat and file) are
 * discarded.
 */
enum class LogLevel {
    VERBOSE, DEBUG, INFO, WARN, ERROR, NONE
}

/**
 * Persists logs to the app's storage so debugging works without adb attached.
 *
 * Logs are written under the external private dir `getExternalFilesDir/logs/` (falling back
 * to internal `filesDir/logs/` when unavailable), one file per day (log-yyyy-MM-dd.txt).
 * The external private dir can be viewed directly via a file manager at
 * `/storage/emulated/0/Android/data/<package>/files/logs/` — no root needed.
 * All writes are serialized onto a single background thread, avoiding main-thread blocking
 * and multi-thread interleaving. A copy is mirrored to [android.util.Log].
 *
 * Level filtering is supported: logs below [minLevel] (both the logcat mirror and the file)
 * are skipped. The level is persisted by `LogSettingsRepository` and synced by
 * `AIEditorApp` via [setMinLevel] at startup and on settings changes.
 * Defaults to [LogLevel.VERBOSE] during development (full logging).
 *
 * Call [init] once in [Application.onCreate] before use.
 */
object FileLogger {

    private const val TAG = "FileLogger"
    private const val MAX_AGE_DAYS = 7
    private const val MAX_FILE_BYTES = 5 * 1024 * 1024 // 5MB cap per log file (grows fairly fast at VERBOSE)
    /** Coalesced-flush delay: once a batch of logs has been quiet this long, flush to disk. ERROR doesn't wait — it flushes immediately. */
    private const val FLUSH_DELAY_MS = 500L

    /** Single thread serializing all disk writes; also serves as the scheduler for delayed coalesced flushes. */
    private val ioExecutor = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "file-logger").apply { isDaemon = true }
    }
    private val fileNameFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneId.systemDefault())
    private val timestampFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(java.time.ZoneId.systemDefault())

    @Volatile
    private var logDir: File? = null

    // The fields below are accessed only by the single ioExecutor thread; no sync needed.
    private var writer: java.io.BufferedWriter? = null
    private var writerDate: String? = null
    private var flushScheduled = false

    /** Current minimum logged level; anything below it is skipped. Default depends on build type: debug=VERBOSE (full during development), release=INFO. */
    @Volatile
    var minLevel: LogLevel = LogLevel.VERBOSE
        private set

    /** Sets the minimum logged level (thread-safe). Called by settings/startup sync. */
    fun setMinLevel(level: LogLevel) {
        if (minLevel != level) {
            minLevel = level
            // The level change itself goes to logcat so the new threshold can't filter it out
            Log.i(TAG, "Log level switched to $level")
        }
    }

    private fun shouldLog(level: LogLevel): Boolean = level.ordinal >= minLevel.ordinal

    /** Initializes the log directory. Safe to call repeatedly. */
    fun init(context: Context) {
        if (logDir != null) return
        // Prefer the external private dir so users can view it with a file manager; fall
        // back to internal storage when unavailable.
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "logs").apply { mkdirs() }
        logDir = dir
        // Default level by build type as a fallback: release doesn't enable full VERBOSE
        // (high-frequency AI tool logs would keep hammering the disk). Applies only when
        // never set — user/persisted settings later override via setMinLevel and always win.
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (minLevel == LogLevel.VERBOSE) {
            minLevel = if (debuggable) LogLevel.VERBOSE else LogLevel.INFO
        }
        ioExecutor.execute { cleanupOldLogs(dir) }
        i(TAG, "FileLogger initialized, log directory: ${dir.absolutePath}")
    }

    fun v(tag: String, message: String) {
        if (!shouldLog(LogLevel.VERBOSE)) return
        Log.v(tag, message)
        write("VERBOSE", tag, message, null)
    }

    fun d(tag: String, message: String) {
        if (!shouldLog(LogLevel.DEBUG)) return
        Log.d(tag, message)
        write("DEBUG", tag, message, null)
    }

    fun i(tag: String, message: String) {
        if (!shouldLog(LogLevel.INFO)) return
        Log.i(tag, message)
        write("INFO", tag, message, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (!shouldLog(LogLevel.WARN)) return
        Log.w(tag, message, throwable)
        write("WARN", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!shouldLog(LogLevel.ERROR)) return
        Log.e(tag, message, throwable)
        write("ERROR", tag, message, throwable)
    }

    /** Returns all current log files sorted by file name (i.e. date), for screens like "View logs". */
    fun listLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith("log-") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * Deletes all log files and returns the number of bytes freed.
     *
     * The deletion must run on [ioExecutor]: today's file is held open by [writer]; without
     * closing it and clearing the date marker first, later writes would keep landing in the
     * deleted inode and logs would silently vanish until the day rolls over.
     */
    fun clearLogs(): Long {
        val dir = logDir ?: return 0L
        val freed = java.util.concurrent.atomic.AtomicLong(0)
        val latch = java.util.concurrent.CountDownLatch(1)
        ioExecutor.execute {
            runCatching {
                writer?.close()
                writer = null
                writerDate = null
                flushScheduled = false
                dir.listFiles { f -> f.isFile && f.name.startsWith("log-") }?.forEach { file ->
                    val size = file.length()
                    if (file.delete()) freed.addAndGet(size)
                }
            }.onFailure { Log.e(TAG, "Failed to clear logs", it) }
            latch.countDown()
        }
        runCatching { latch.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        return freed.get()
    }

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val dir = logDir ?: return // Uninitialized: logcat only, nothing written to disk
        val now = java.time.Instant.now()
        // Media redaction before writing to disk: keeps oversized base64 (images etc.) from
        // bloating the daily log files; logcat still prints it as-is (system truncation
        // protects it).
        val redacted = MediaRedactor.redact(message)
        val line = buildString {
            append(timestampFormat.format(now))
            append(" ").append(level)
            append(" [").append(tag).append("] ")
            append(redacted)
            if (throwable != null) {
                append("\n").append(stackTraceToString(throwable))
            }
            append("\n")
        }
        ioExecutor.execute {
            runCatching {
                val date = fileNameFormat.format(now)
                val file = File(dir, "log-$date.txt")
                // Switch files when the day rolls over; reset and reopen when a single file
                // exceeds the cap. writer is accessed only by this thread.
                // Always open for append: neither a process restart nor a second launch the
                // same day may wipe already-written logs.
                if (writerDate != date) {
                    writer?.close()
                    writer = FileOutputStream(file, true).bufferedWriter()
                    writerDate = date
                } else if (file.length() > MAX_FILE_BYTES) {
                    writer?.close()
                    file.writeText("--- Log file exceeded ${MAX_FILE_BYTES / 1024 / 1024}MB and was reset ---\n")
                    writer = FileOutputStream(file, true).bufferedWriter()
                }
                writer?.append(line)
                // ERROR flushes immediately; everything else coalesces into one flush after
                // FLUSH_DELAY_MS. If the system kills the process during the buffer window we
                // lose those last few hundred milliseconds of logs — don't batch any bigger.
                if (level == "ERROR") {
                    flushWriter()
                } else if (!flushScheduled) {
                    flushScheduled = true
                    ioExecutor.schedule({ runCatching { flushWriter() } }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
                }
            }.onFailure {
                Log.e(TAG, "Failed to write log", it)
            }
        }
    }

    /**
     * Synchronously flushes buffered logs (called on urgent paths like crash handlers).
     * Submitted to the tail of the ioExecutor queue so all previously queued appends have
     * hit disk; blocks the calling thread for at most 2s.
     */
    fun flushSync() {
        val latch = java.util.concurrent.CountDownLatch(1)
        ioExecutor.execute {
            flushWriter()
            latch.countDown()
        }
        runCatching { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) }
    }

    /** Pushes buffered logs to the underlying file. Only call on the single [ioExecutor] thread. */
    private fun flushWriter() {
        runCatching { writer?.flush() }
        flushScheduled = false
    }

    private fun stackTraceToString(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }

    /** Deletes log files older than [MAX_AGE_DAYS] days. */
    private fun cleanupOldLogs(dir: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24L * 60 * 60 * 1000
        dir.listFiles { f -> f.isFile && f.name.startsWith("log-") }?.forEach { file ->
            if (file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }
}
