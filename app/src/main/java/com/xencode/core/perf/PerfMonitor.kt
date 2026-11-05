package com.xencode.core.perf

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.StatFs
import android.os.SystemClock
import android.view.Choreographer
import com.xencode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A single sample point (about one per second). */
data class PerfSample(
    /** Epoch milliseconds. */
    val t: Long,
    /** Module the user was in at sampling time (mapped from the nav route, see [setModule]). */
    val module: String,
    /** This process's CPU usage as a percentage of total device capacity (all cores = 100%). */
    val cpuPct: Float,
    val pssMb: Float,
    val rssMb: Float,
    val javaHeapMb: Float,
    val javaHeapMaxMb: Float,
    val nativePssMb: Float,
    val threads: Int,
    /** Frames rendered in the last 1 second. */
    val fps: Int,
    /** Janky frames in the last 1 second (frame interval > 33ms). */
    val jank: Int
)

/** Process cumulative usage (since sampling started). */
data class PerfTotals(
    val startedAt: Long = 0L,
    val samples: Int = 0,
    val frames: Long = 0L,
    val jankFrames: Long = 0L,
    val peakRssMb: Float = 0f,
    /** Process cumulative CPU time in milliseconds (utime+stime converted). */
    val totalCpuMs: Long = 0L
)

/** Whole-device snapshot. */
data class PerfSystem(
    val memTotalMb: Float = 0f,
    val memAvailMb: Float = 0f,
    val storageTotalMb: Float = 0f,
    val storageAvailMb: Float = 0f,
    val cpuCores: Int = Runtime.getRuntime().availableProcessors()
)

/** Overview of persisted record files. */
data class PerfFilesInfo(
    val count: Int = 0,
    val totalBytes: Long = 0L,
    val oldest: Long = 0L,
    val newest: Long = 0L
)

/**
 * Performance sampler: feeds the "Performance panel" with live usage, per-module aggregation,
 * and long-term persisted data.
 *
 * Design trade-offs (pragmatic):
 * - Sampling is started manually by the user (Settings → Data & diagnostics → Performance
 *   panel), one point every [SAMPLE_INTERVAL_MS];
 * - Live data uses an in-memory ring buffer (last [RING_MAX] points, enough for 1 hour), with
 *   aggregation computed on the fly in the UI layer;
 * - Long-term analysis relies on JSONL files on disk (`filesDir/perf/perf-yyyyMMdd.jsonl`, one
 *   file per day, auto-numbered past 8MB), keeping the last [KEEP_DAYS] days with old files
 *   pruned automatically;
 * - Module attribution doesn't instrument each screen; it simply reuses MainActivity's nav
 *   routes (chat/settings/terminal/…), passively received via [setModule];
 * - CPU usage is computed as a percentage of total device capacity (all cores = 100%), the
 *   same convention as system monitoring tools.
 *
 * Sampling itself only reads /proc and does one appendText, so the overhead is negligible;
 * FPS is measured via Choreographer frame callbacks.
 */
@Singleton
class PerfMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "PerfMonitor"
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val RING_MAX = 3_600

        /**
         * Sample window (number of points) handed to the UI.
         *
         * The ring buffer keeps [RING_MAX] points for export/long-term analysis, but do **not**
         * copy the whole thing to the UI: toList()-ing a 3600-item list every second and letting
         * the Compose side re-run groupBy over all of it means a large allocation + an O(n) pass
         * every second — measured to be one of the culprits behind the heap sawtoothing between
         * 24↔136MB (GC churn) and dropping to 15fps.
         * The live panel only needs the last few minutes, so the window is 180 (3 minutes).
         */
        const val UI_SAMPLE_WINDOW = 180
        const val KEEP_DAYS = 7
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
        const val DIR = "perf"
        val DAY_FORMAT = SimpleDateFormat("yyyyMMdd", Locale.US)
        val TS_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        /** Jank threshold: frame interval > 33ms (about 2 vsyncs at 60Hz). */
        const val JANK_THRESHOLD_NS = 33_000_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val choreographer = Choreographer.getInstance()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _module = MutableStateFlow("chat")
    val module: StateFlow<String> = _module.asStateFlow()

    private val _samples = MutableStateFlow<List<PerfSample>>(emptyList())
    /** The last [RING_MAX] sample points; the UI consumes them once a second for live display and aggregation. */
    val samples: StateFlow<List<PerfSample>> = _samples.asStateFlow()

    private val _totals = MutableStateFlow(PerfTotals())
    val totals: StateFlow<PerfTotals> = _totals.asStateFlow()

    private val _system = MutableStateFlow(PerfSystem())
    val system: StateFlow<PerfSystem> = _system.asStateFlow()

    private val _filesInfo = MutableStateFlow(PerfFilesInfo())
    val filesInfo: StateFlow<PerfFilesInfo> = _filesInfo.asStateFlow()

    private val ring = ArrayDeque<PerfSample>(RING_MAX)
    private var samplingJob: Job? = null
    private var fileInfoTick = 0

    // ── FPS frame callbacks ─────────────────────────────────────────────
    private val frameTimesNs = ArrayDeque<Long>(240)
    private val frameLock = Any()
    private var frameCallback: Choreographer.FrameCallback? = null

    private val frameCallbackImpl = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            synchronized(frameLock) {
                // Sampling has stopped (stopFrameCallbacks nulls frameCallback): break the
                // re-posting chain immediately. Otherwise the in-flight callback at the moment
                // of stopping would post itself back and spin on every frame forever.
                if (frameCallback == null) return
                frameTimesNs.addLast(System.nanoTime())
                while (frameTimesNs.size > 240) frameTimesNs.removeFirst()
            }
            // Keep posting the next frame; [stopFrameCallbacks] removes it when sampling stops.
            choreographer.postFrameCallback(this)
        }
    }

    init {
        scope.launch(Dispatchers.IO) {
            pruneOldFiles()
            refreshFilesInfo()
            _system.value = readSystemSnapshot()
        }
    }

    /** Called by MainActivity when the nav route changes. */
    fun setModule(key: String) {
        if (_module.value != key) _module.value = key
    }

    fun setEnabled(on: Boolean) {
        if (_enabled.value == on) return
        _enabled.value = on
        samplingJob?.cancel()
        samplingJob = null
        if (on) {
            startFrameCallbacks()
            samplingJob = scope.launch { sampleLoop() }
            FileLogger.i(TAG, "Performance sampling started")
        } else {
            stopFrameCallbacks()
            FileLogger.i(TAG, "Performance sampling stopped")
        }
    }

    // ── Export / cleanup ────────────────────────────────────────────

    /** Merges all record files into a single JSONL and returns the exported file in the cache (the caller decides where to save it). */
    suspend fun exportRawFile(): File = withContext(Dispatchers.IO) {
        val out = File(context.cacheDir, "xencode-perf-raw-${TS_FORMAT.format(Date())}.jsonl")
        out.bufferedWriter().use { writer ->
            perfFilesSorted().forEach { f ->
                f.forEachLine { writer.appendLine(it) }
            }
        }
        out
    }

    /** Aggregates all historical samples by module and exports a CSV (module,samples,seconds,avg_cpu_pct,avg_pss_mb,max_pss_mb). */
    suspend fun exportSummaryCsv(): File = withContext(Dispatchers.IO) {
        data class Agg(var samples: Int = 0, var cpuSum: Double = 0.0, var pssSum: Double = 0.0, var pssMax: Float = 0f)
        val agg = linkedMapOf<String, Agg>()
        perfFilesSorted().forEach { f ->
            f.forEachLine { line ->
                runCatching {
                    val o = JSONObject(line)
                    val m = o.optString("module", "other")
                    val a = agg.getOrPut(m) { Agg() }
                    a.samples++
                    a.cpuSum += o.optDouble("cpu_pct", 0.0)
                    val pss = o.optDouble("pss_mb", 0.0).toFloat()
                    a.pssSum += pss
                    if (pss > a.pssMax) a.pssMax = pss
                }
            }
        }
        val out = File(context.cacheDir, "xencode-perf-summary-${TS_FORMAT.format(Date())}.csv")
        out.bufferedWriter().use { w ->
            w.appendLine("module,samples,seconds,avg_cpu_pct,avg_pss_mb,max_pss_mb")
            agg.toSortedMap().forEach { (m, a) ->
                val avgCpu = if (a.samples > 0) a.cpuSum / a.samples else 0.0
                val avgPss = if (a.samples > 0) a.pssSum / a.samples else 0.0
                w.appendLine("$m,${a.samples},${a.samples},${"%.2f".format(avgCpu)},${"%.1f".format(avgPss)},${"%.1f".format(a.pssMax)}")
            }
        }
        out
    }

    /** Clears all persisted records (the in-memory ring buffer is kept). */
    fun clearHistory() {
        scope.launch(Dispatchers.IO) {
            perfDir().listFiles()?.forEach { it.delete() }
            refreshFilesInfo()
        }
    }

    // ── Sampling main loop ─────────────────────────────────────────────

    private suspend fun sampleLoop() {
        var lastSelfTicks = readSelfTicks()
        var lastDeviceTotal = readDeviceTotalTicks()
        var lastWallMs = SystemClock.elapsedRealtime()
        var lastCpuMs = selfCpuMs()
        while (scope.isActive && _enabled.value) {
            delay(SAMPLE_INTERVAL_MS)
            val nowWall = SystemClock.elapsedRealtime()
            val wallDeltaMs = (nowWall - lastWallMs).coerceAtLeast(1)
            lastWallMs = nowWall

            val selfTicks = readSelfTicks()
            val deviceTotal = readDeviceTotalTicks()
            val selfDelta = (selfTicks - lastSelfTicks).coerceAtLeast(0)
            val rawDeviceDelta = (deviceTotal - lastDeviceTotal).coerceAtLeast(0)
            lastSelfTicks = selfTicks
            lastDeviceTotal = deviceTotal
            // On some devices /proc/stat is unreadable or always returns 0 (observed: OnePlus
            // Android 16); using it as the denominator inflates CPU% a hundredfold — exported
            // user data showed 6600% when the reality was selfDelta=66 ticks. When the
            // denominator is untrusted, fall back to the whole-device capacity of
            // core count × ticksPerSecond, keeping values in the sane 0..(cores×100)% range.
            val capacityTicks = (
                Runtime.getRuntime().availableProcessors().coerceAtLeast(1) *
                    ticksPerSecond().toLong()
                ).coerceAtLeast(1L)
            val deviceDelta =
                if (rawDeviceDelta in 1 until capacityTicks * 8) rawDeviceDelta else capacityTicks
            val cpuPct = selfDelta * 100f / deviceDelta

            val mem = readMemoryInfo()
            val (fps, jank) = drainFrameStats()

            val sample = PerfSample(
                t = System.currentTimeMillis(),
                module = _module.value,
                cpuPct = cpuPct,
                pssMb = mem.totalPssMb,
                rssMb = mem.rssMb,
                javaHeapMb = mem.javaHeapMb,
                javaHeapMaxMb = mem.javaHeapMaxMb,
                nativePssMb = mem.nativePssMb,
                threads = mem.threads,
                fps = fps,
                jank = jank
            )

            ring.addLast(sample)
            while (ring.size > RING_MAX) ring.removeFirst()
            // Hand only the last UI_SAMPLE_WINDOW points to the UI, avoiding a full 3600-item
            // buffer copy every second (see the UI_SAMPLE_WINDOW comment: a culprit behind the
            // GC sawtooth/frame drops).
            _samples.value = if (ring.size <= UI_SAMPLE_WINDOW) {
                ring.toList()
            } else {
                ring.drop(ring.size - UI_SAMPLE_WINDOW)
            }

            val nowCpuMs = selfCpuMs()
            val cpuMsDelta = (nowCpuMs - lastCpuMs).coerceAtLeast(0)
            lastCpuMs = nowCpuMs
            _totals.value = _totals.value.let {
                PerfTotals(
                    startedAt = if (it.startedAt == 0L) sample.t else it.startedAt,
                    samples = it.samples + 1,
                    frames = it.frames + fps,
                    jankFrames = it.jankFrames + jank,
                    peakRssMb = maxOf(it.peakRssMb, sample.rssMb),
                    totalCpuMs = it.totalCpuMs + cpuMsDelta
                )
            }

            withContext(Dispatchers.IO) {
                runCatching { appendToFile(sample) }
                    .onFailure { FileLogger.w(TAG, "Failed to write performance record: ${it.message}") }
                if (++fileInfoTick % 60 == 0) {
                    _system.value = readSystemSnapshot()
                    refreshFilesInfo()
                }
            }
        }
    }

    // ── Low-level reads ───────────────────────────────────────────────

    private data class MemInfo(
        val totalPssMb: Float,
        val nativePssMb: Float,
        val rssMb: Float,
        val javaHeapMb: Float,
        val javaHeapMaxMb: Float,
        val threads: Int
    )

    private fun readMemoryInfo(): MemInfo {
        // Prefer /proc/self/smaps_rollup: a plain file read with no binder IPC, giving live
        // and accurate PSS. We used to call ActivityManager.getProcessMemoryInfo once a second
        // — that's a synchronous IPC that blocks the sampling thread when the system is busy;
        // and some devices return cached values: we measured PSS pinned at 350.7MB without
        // moving, useless for observation. Fall back to IPC only when rollup is unavailable.
        var totalPssKb = readRollupKb("Pss:")
        var nativePssKb = 0L
        if (totalPssKb <= 0) {
            runCatching {
                val info = activityManager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()
                if (info != null) {
                    totalPssKb = info.totalPss.toLong()
                    nativePssKb = info.nativePss.toLong()
                }
            }
        }
        val rt = Runtime.getRuntime()
        val usedHeap = rt.totalMemory() - rt.freeMemory()
        return MemInfo(
            totalPssMb = totalPssKb / 1024f,
            nativePssMb = nativePssKb / 1024f,
            rssMb = readProcStatusKb("VmRSS:") / 1024f,
            javaHeapMb = usedHeap / (1024f * 1024f),
            javaHeapMaxMb = rt.maxMemory() / (1024f * 1024f),
            threads = readProcStatusKb("Threads:").toInt()
        )
    }

    /** Reads a field of the form `Pss:  1234 kB` from /proc/self/smaps_rollup (KB, no IPC). */
    private fun readRollupKb(key: String): Long {
        return runCatching {
            File("/proc/self/smaps_rollup").useLines { lines ->
                lines.firstOrNull { it.startsWith(key) }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.split(' ')
                    ?.firstOrNull()
                    ?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)
    }

    private fun readProcStatusKb(key: String): Long {
        return runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith(key) }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.split(' ')
                    ?.firstOrNull()
                    ?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)
    }

    /** utime+stime from /proc/self/stat (clock ticks). */
    private fun readSelfTicks(): Long {
        return runCatching {
            val line = File("/proc/self/stat").readText()
            val after = line.substringAfterLast(')').trim()
            val t = after.split(Regex("\\s+"))
            // after starts at field3(state); utime=field14 → index 11, stime=field15 → index 12.
            (t[11].toLongOrNull() ?: 0L) + (t[12].toLongOrNull() ?: 0L)
        }.getOrDefault(0L)
    }

    /** Process cumulative CPU milliseconds (since process start). */
    private fun selfCpuMs(): Long = readSelfTicks() * 1000L / ticksPerSecond()

    /** Cumulative ticks across all cores from the first line of /proc/stat (user+nice+system+idle+iowait+irq+softirq+steal). */
    private fun readDeviceTotalTicks(): Long {
        return runCatching {
            File("/proc/stat").bufferedReader().use { reader ->
                val line = reader.readLine() ?: return 0L
                line.removePrefix("cpu").trim().split(Regex("\\s+"))
                    .sumOf { it.toLongOrNull() ?: 0L }
            }
        }.getOrDefault(1L)
    }

    private fun readSystemSnapshot(): PerfSystem {
        var memTotal = 0L
        var memAvail = 0L
        runCatching {
            File("/proc/meminfo").useLines { lines ->
                lines.forEach { l ->
                    when {
                        l.startsWith("MemTotal:") -> memTotal = l.filterKb()
                        l.startsWith("MemAvailable:") -> memAvail = l.filterKb()
                    }
                }
            }
        }
        var storageTotal = 0L
        var storageAvail = 0L
        runCatching {
            val stat = StatFs(context.filesDir.absolutePath)
            storageTotal = stat.totalBytes
            storageAvail = stat.availableBytes
        }
        return PerfSystem(
            memTotalMb = memTotal / 1024f,
            memAvailMb = memAvail / 1024f,
            storageTotalMb = storageTotal / (1024f * 1024f),
            storageAvailMb = storageAvail / (1024f * 1024f)
        )
    }

    private fun String.filterKb(): Long =
        substringAfter(':').trim().split(' ').firstOrNull()?.toLongOrNull() ?: 0L

    private fun drainFrameStats(): Pair<Int, Int> {
        val now = System.nanoTime()
        val window = 1_000_000_000L
        synchronized(frameLock) {
            var frames = 0
            var jank = 0
            var prev = 0L
            val it = frameTimesNs.iterator()
            while (it.hasNext()) {
                val t = it.next()
                if (now - t > window) { it.remove(); continue }
                if (prev != 0L && t - prev > JANK_THRESHOLD_NS) jank++
                prev = t
                frames++
            }
            return frames to jank
        }
    }

    // ── Persistence ─────────────────────────────────────────────────

    private fun perfDir(): File = File(context.filesDir, DIR).apply { mkdirs() }

    private fun perfFilesSorted(): List<File> =
        perfDir().listFiles { f -> f.name.endsWith(".jsonl") }
            ?.sortedBy { it.name } ?: emptyList()

    private fun appendToFile(sample: PerfSample) {
        val day = DAY_FORMAT.format(Date(sample.t))
        var f = File(perfDir(), "perf-$day.jsonl")
        var idx = 2
        while (f.exists() && f.length() > MAX_FILE_BYTES) {
            f = File(perfDir(), "perf-$day-$idx.jsonl")
            idx++
        }
        f.appendText(sample.toJson().toString() + "\n")
    }

    private fun pruneOldFiles() {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000
        perfFilesSorted().forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
    }

    private fun refreshFilesInfo() {
        val files = perfFilesSorted()
        _filesInfo.value = PerfFilesInfo(
            count = files.size,
            totalBytes = files.sumOf { it.length() },
            oldest = files.minOfOrNull { it.lastModified() } ?: 0L,
            newest = files.maxOfOrNull { it.lastModified() } ?: 0L
        )
    }

    private fun startFrameCallbacks() {
        if (frameCallback != null) return
        frameCallback = frameCallbackImpl
        runCatching { choreographer.postFrameCallback(frameCallbackImpl) }
    }

    private fun stopFrameCallbacks() {
        val cb = frameCallback ?: return
        runCatching { choreographer.removeFrameCallback(cb) }
        frameCallback = null
        synchronized(frameLock) { frameTimesNs.clear() }
    }

    private fun PerfSample.toJson(): JSONObject = JSONObject().apply {
        put("t", t)
        put("module", module)
        put("cpu_pct", cpuPct.toDouble())
        put("pss_mb", pssMb.toDouble())
        put("rss_mb", rssMb.toDouble())
        put("java_heap_mb", javaHeapMb.toDouble())
        put("java_heap_max_mb", javaHeapMaxMb.toDouble())
        put("native_pss_mb", nativePssMb.toDouble())
        put("threads", threads)
        put("fps", fps)
        put("jank", jank)
    }
}

/** Clock ticks per second: prefer sysconf(_SC_CLK_TCK), falling back to the common Linux value of 100. */
private fun ticksPerSecond(): Long = runCatching {
    android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
}.getOrDefault(100L)
