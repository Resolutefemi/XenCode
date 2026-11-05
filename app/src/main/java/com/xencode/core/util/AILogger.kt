package com.xencode.core.util

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Full request / response" logs for AI providers: **one file per session (sessionId)**,
 * persisting the URL, request body, and response of every call in detail, so model
 * interaction issues can be diagnosed offline without a packet-capture tool.
 *
 * Independent of [FileLogger] (the general app log split by day): this class archives by
 * session, with larger volume and fuller content (full conversation history, tool
 * definitions, raw SSE stream), so it gets its own files and its own cleanup.
 *
 * Files land in the external private dir `getExternalFilesDir/ai-logs/session-<id>.log`
 * (falling back to internal `filesDir/ai-logs/` when unavailable). All writes are
 * serialized onto a single background thread and never block the caller's coroutine.
 * The request body contains no API key (the key travels in HTTP headers; this class only
 * logs URL and body), so it is safe to retain.
 *
 * No length truncation: request/response bodies are serialized straight to disk on the
 * writer thread without building one giant string; raw SSE is flushed in batches — so long
 * histories / large attachments never OOM, and logs stay complete for reproducing issues.
 * Oversized base64 media (images etc.) is redacted to a placeholder based on the **JSON
 * structure** (field name + value shape) before writing, keeping multi-MB lines from
 * bloating the log file.
 *
 * Call [init] once in [android.app.Application.onCreate] before use.
 */
object AILogger {

    private const val TAG = "AILogger"
    private const val MAX_AGE_DAYS = 7
    private const val MAX_FILE_BYTES = 20 * 1024 * 1024 // Per-session file cap of 20MB (the full history is resent every turn, so it grows fast)
    /** Buffer threshold (characters) for flushing raw SSE in batches: flush once this much accumulates, so the whole response never sits in memory. */
    private const val SSE_FLUSH_CHARS = 64 * 1024
    /** When the field name suggests base64 content, values longer than this are treated as media data. */
    private const val BASE64_MIN_CHARS = 256
    /** Fallback: a run of consecutive base64 characters longer than this is treated as media data anywhere, whatever the field is called. */
    private const val BASE64_RUN_CHARS = 1024

    /** Common field names that carry base64 (Anthropic `source.data`, attachment `base64Data`, OpenAI `image_url.url`, etc.). */
    private val BASE64_FIELD_NAMES = setOf("data", "base64Data", "image_data", "url")

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-logger").apply { isDaemon = true }
    }
    private val timestampFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(java.time.ZoneId.systemDefault())
    // Aligned with Retrofit's GsonConverter behavior (default field names, skipping nulls),
    // plus pretty-printing for readability; HTML escaping is disabled so < > & in prompts
    // aren't turned into entities that hurt readability.
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    @Volatile
    private var logDir: File? = null

    /** Per-session call counter: pairs the REQUEST / RESPONSE of the same interaction with the same sequence number. */
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    /** Initializes the log directory. Safe to call repeatedly. */
    fun init(context: Context) {
        if (logDir != null) return
        // Prefer the external private dir, easier to pull (under root or adb); fall back to
        // internal storage when unavailable.
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "ai-logs").apply { mkdirs() }
        logDir = dir
        ioExecutor.execute { cleanupOldLogs(dir) }
        FileLogger.i(TAG, "AILogger initialized, AI session log directory: ${dir.absolutePath}")
    }

    /**
     * Logs a request's URL and body and increments the session counter (serving as this
     * interaction's sequence number).
     *
     * @return The assigned sequence number `n`; the caller must pass it back verbatim to the
     *   matching [logResponse] / [logError] / [beginRawSse], otherwise concurrent requests
     *   within a session (e.g. title generation racing the main request) misnumber REQUEST
     *   vs RESPONSE/ERROR — a late response would read the latest counter value.
     */
    fun logRequest(sessionId: String?, provider: String, model: String, method: String, url: String, body: Any?): Int {
        val n = counter(sessionId).incrementAndGet()
        appendToSession(sessionId) { w ->
            w.write("\n")
            w.write("=".repeat(78))
            w.write("\n")
            w.write("${now()}  REQUEST #$n   [$provider / $model]\n")
            w.write("$method $url\n")
            w.write("--- request body ---\n")
            writeBody(w, body)
            w.write("\n")
        }
        return n
    }

    /** Logs a non-streaming response object (serialized to JSON with Gson). [seq] must come from the matching [logRequest]'s return value. */
    fun logResponse(sessionId: String?, provider: String, body: Any?, seq: Int) {
        appendToSession(sessionId) { w ->
            w.write("${now()}  RESPONSE #$seq   [$provider]\n")
            w.write("--- response body ---\n")
            writeBody(w, body)
            w.write("\n")
        }
    }

    /** Logs a request failure (cancellation doesn't count as failure and shouldn't reach here). [seq] must come from the matching [logRequest]'s return value. */
    fun logError(sessionId: String?, provider: String, throwable: Throwable, seq: Int) {
        appendToSession(sessionId) { w ->
            w.write("${now()}  ERROR #$seq   [$provider]\n")
            w.write("${throwable.javaClass.name}: ${throwable.message ?: ""}\n")
        }
    }

    /**
     * Opens a raw SSE log handle for one streaming response: the caller calls
     * [RawSseLog.append] for each received line, and calls [RawSseLog.finish] in `finally`
     * when the turn ends (success / failure / cancellation). Content is flushed in batches
     * and never held in memory as a whole.
     *
     * @param seq Must come from the matching [logRequest]'s return value.
     */
    fun beginRawSse(sessionId: String?, provider: String, seq: Int): RawSseLog =
        RawSseLog(sessionId, provider, seq)

    /** Buffered raw SSE log handle for one streaming response, flushed in batches as it reaches the threshold. */
    class RawSseLog internal constructor(
        private val sessionId: String?,
        private val provider: String,
        private val seq: Int
    ) {
        private val buffer = StringBuilder()
        private var headerWritten = false

        fun append(line: String) {
            buffer.append(redactString(line, null)).append('\n')
            if (buffer.length >= SSE_FLUSH_CHARS) flush()
        }

        fun finish() {
            if (buffer.isEmpty() && !headerWritten) {
                headerWritten = true
                writeChunk(header() + "(empty response)\n")
            } else {
                flush()
            }
        }

        private fun flush() {
            if (buffer.isEmpty() && headerWritten) return
            val chunk = buffer.toString()
            buffer.setLength(0)
            val prefix = if (!headerWritten) {
                headerWritten = true
                header()
            } else ""
            writeChunk(prefix + chunk)
        }

        private fun header(): String =
            "${now()}  RESPONSE #$seq   [$provider / stream]\n--- raw SSE ---\n"

        private fun writeChunk(text: String) {
            appendToSession(sessionId) { w -> w.write(text) }
        }
    }

    private fun counter(sessionId: String?): AtomicInteger =
        counters.getOrPut(sessionId ?: "unknown") { AtomicInteger(0) }

    /** Returns all current session log files sorted by file name, for usage stats and cleanup. */
    fun listLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith("session-") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * Deletes all session logs and returns the number of bytes freed.
     *
     * Runs on [ioExecutor] to avoid interleaving with queued append writes (each write here
     * opens for append and closes immediately, holding no handle).
     * Also resets the call counters, so after clearing, new logs start from #1.
     */
    fun clearLogs(): Long {
        val dir = logDir ?: return 0L
        val freed = java.util.concurrent.atomic.AtomicLong(0)
        val latch = java.util.concurrent.CountDownLatch(1)
        ioExecutor.execute {
            runCatching {
                dir.listFiles { f -> f.isFile && f.name.startsWith("session-") }?.forEach { file ->
                    val size = file.length()
                    if (file.delete()) freed.addAndGet(size)
                }
                counters.clear()
            }.onFailure { Log.e(TAG, "Failed to clear AI session logs", it) }
            latch.countDown()
        }
        runCatching { latch.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        return freed.get()
    }

    private fun now(): String = timestampFormat.format(java.time.Instant.now())

    /** Writes a redacted body to [w] streaming: objects go through Gson tree serialization (no whole-string construction). */
    private fun writeBody(w: Writer, body: Any?) {
        when (body) {
            null -> w.write("null")
            is String -> w.write(redactString(body, null))
            else -> {
                val tree = runCatching { gson.toJsonTree(body) }.getOrNull()
                if (tree == null) {
                    w.write(redactString(body.toString(), null))
                } else {
                    gson.toJson(redactInPlace(tree), w)
                }
            }
        }
    }

    /** For tests: serializes a body to JSON text using the log redaction rules (without writing to disk). */
    internal fun redactToJson(body: Any?): String =
        java.io.StringWriter().also { writeBody(it, body) }.toString()

    /** Recursively walks the JSON tree, replacing media base64 with placeholders in place (the tree is freshly built by [GsonBuilder], safe to mutate). */
    private fun redactInPlace(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> {
            element.keySet().toList().forEach { key ->
                val value = element.get(key)
                when {
                    value is JsonObject || value is JsonArray -> redactInPlace(value)
                    value is JsonPrimitive && value.isString ->
                        element.add(key, JsonPrimitive(redactString(value.asString, key)))
                }
            }
            element
        }
        is JsonArray -> {
            for (i in 0 until element.size()) {
                val value = element.get(i)
                when {
                    value is JsonObject || value is JsonArray -> redactInPlace(value)
                    value is JsonPrimitive && value.isString ->
                        element.set(i, JsonPrimitive(redactString(value.asString, null)))
                }
            }
            element
        }
        else -> element
    }

    /**
     * Redacts a single string value: replaces inline data URLs, bare base64 judged by field
     * name, and over-long base64 runs anywhere with placeholders. Matching happens within a
     * single value only, never across fields, avoiding the whole-text-regex fragility where a
     * change of format slips through.
     */
    private fun redactString(value: String, key: String?): String {
        if (value.isEmpty()) return value
        var out = DATA_URL_REGEX.replace(value) { m -> "[base64 omitted: ${m.value.length} chars]" }
        if (key != null && key in BASE64_FIELD_NAMES && out.length >= BASE64_MIN_CHARS && out.all { it.isBase64Char() }) {
            return "[base64 omitted: ${out.length} chars]"
        }
        out = LONG_BASE64_RUN_REGEX.replace(out) { m -> "[base64 omitted: ${m.value.length} chars]" }
        return out
    }

    private fun Char.isBase64Char(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' ||
            this == '+' || this == '/' || this == '=' || this == '_' || this == '-'

    /** Opens the session log file (append mode) on the single background thread and runs the write block; resets the file first when it exceeds the cap. */
    private fun appendToSession(sessionId: String?, block: (Writer) -> Unit) {
        val dir = logDir ?: return // Drop silently when uninitialized, avoiding error spam with no directory
        val safeId = (sessionId ?: "unknown").replace(Regex("[^A-Za-z0-9_-]"), "_")
        ioExecutor.execute {
            runCatching {
                val file = File(dir, "session-$safeId.log")
                if (file.length() > MAX_FILE_BYTES) {
                    // Over the cap: truncate and start over, so a single file can't grow unbounded.
                    file.writeText("--- AI session log exceeded ${MAX_FILE_BYTES / 1024 / 1024}MB and was reset ---\n")
                }
                BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)).use { w ->
                    block(w)
                    w.flush()
                }
            }.onFailure { Log.e(TAG, "Failed to write AI session log", it) }
        }
    }

    /** Deletes session log files untouched for more than [MAX_AGE_DAYS] days. */
    private fun cleanupOldLogs(dir: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24L * 60 * 60 * 1000
        dir.listFiles { f -> f.isFile && f.name.startsWith("session-") }?.forEach { file ->
            if (file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }

    /** Inline base64 data URL (`data:<mime>;base64,...`, mime may be empty/anything). */
    private val DATA_URL_REGEX = Regex("data:[A-Za-z0-9.+/-]*;base64,[A-Za-z0-9+/=_-]{$BASE64_MIN_CHARS,}")
    /** Fallback: any run of >= [BASE64_RUN_CHARS] consecutive base64 characters. */
    private val LONG_BASE64_RUN_REGEX = Regex("[A-Za-z0-9+/=_-]{$BASE64_RUN_CHARS,}")
}
