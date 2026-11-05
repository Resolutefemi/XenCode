package com.xencode.feature.agent.domain.mcp

import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.container.ContainerProfile
import com.xencode.feature.agent.domain.container.LinuxContainerEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.BufferedWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP "stdio" transport implementation: runs the MCP server as a long-lived in-container subprocess,
 * writing one JSON-RPC line to its **stdin** and reading one JSON-RPC line from its **stdout** (NDJSON);
 * **stderr** is consumed separately as server logs and never pollutes the protocol stream.
 *
 * The process is started inside the proot container by [LinuxContainerEngine.startStdioProcess] (e.g. `npx -y some-server`),
 * lazily on the first [request]/[notify] (serialized by [startMutex]). The stdout read loop routes each id-carrying response to
 * its pending [CompletableDeferred]; server-initiated requests/notifications (with a method) are unsupported for now — logged and ignored.
 *
 * Peer of [StreamableHttpTransport]: protocol errors are thrown as [McpException], and [McpClient]/[McpManager]
 * handle them per server, so one server failing does not affect other servers or the whole Agent.
 */
class StdioTransport(
    private val serverName: String,
    private val engine: LinuxContainerEngine,
    private val program: String,
    private val programArgs: List<String>,
    private val projectPath: String?,
    private val extraEnv: Map<String, String> = emptyMap(),
    private val runtimeProfile: ContainerProfile = ContainerProfile.BUILTIN_ALPINE,
    private val json: Json = DEFAULT_JSON
) : McpTransport {

    private companion object {
        const val TAG = "McpStdioTransport"

        /** Upper bound for waiting on a single response: the first `npx -y` run may need to download over the network, so be generous. */
        const val REQUEST_TIMEOUT_MS = 120_000L

        /** Upper bound for waiting for the process to exit after stdout closes: used to fetch the exit code. */
        const val EXIT_WAIT_MS = 2_000L

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val writeMutex = Mutex()
    private val idCounter = AtomicLong(0)

    /** Requests sent and awaiting a response: id → pending response. */
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonRpcResponse>>()

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var closed = false

    override suspend fun request(method: String, params: JsonObject?): JsonRpcResponse {
        ensureStarted()
        val id = idCounter.incrementAndGet()
        val payload = JsonRpcRequest(id = id, method = method, params = params)
        val deferred = CompletableDeferred<JsonRpcResponse>()
        pending[id] = deferred
        FileLogger.d(TAG, "→ [$serverName] $method id=$id")

        try {
            writeLine(json.encodeToString(JsonRpcRequest.serializer(), payload))
        } catch (e: Exception) {
            pending.remove(id)
            throw McpException(message = "[$serverName] Failed to write to stdin: ${e.message}", cause = e)
        }

        val response = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { deferred.await() }
        if (response == null) {
            pending.remove(id)
            throw McpException(message = "[$serverName] Request $method timed out (${REQUEST_TIMEOUT_MS}ms)")
        }
        response.error?.let {
            throw McpException(rpcCode = it.code, message = "$method returned an error [${it.code}] ${it.message}")
        }
        return response
    }

    override suspend fun notify(method: String, params: JsonObject?) {
        ensureStarted()
        val payload = JsonRpcNotification(method = method, params = params)
        FileLogger.d(TAG, "→ notify [$serverName] $method")
        runCatching { writeLine(json.encodeToString(JsonRpcNotification.serializer(), payload)) }
            .onFailure { FileLogger.w(TAG, "[$serverName] Failed to send notification $method: ${it.message}") }
    }

    override fun close() {
        if (closed) return
        closed = true
        FileLogger.i(TAG, "[$serverName] closing stdio transport")
        scope.cancel()
        runCatching { writer?.close() }
        runCatching { process?.destroy() }
        writer = null
        process = null
        failAllPending("transport closed")
    }

    /** Lazily start the subprocess and attach the stdout read loop / stderr drain. Serialized to avoid duplicate concurrent starts. */
    private suspend fun ensureStarted() = startMutex.withLock {
        if (process != null) return@withLock
        if (closed) throw McpException(message = "[$serverName] transport closed")

        FileLogger.i(TAG, "[$serverName] starting stdio server: $program ${programArgs.joinToString(" ")}")
        val p = try {
            engine.startStdioProcess(program, programArgs, projectPath, extraEnv, runtimeProfile)
        } catch (e: Exception) {
            throw McpException(message = "[$serverName] Failed to start subprocess: ${e.message}", cause = e)
        }
        process = p
        writer = p.outputStream.bufferedWriter()
        scope.launch { readLoop(p) }
        scope.launch { drainStderr(p) }
    }

    /** Serialize writing one line (multiple concurrent requests share a single stdin). */
    private suspend fun writeLine(line: String) = writeMutex.withLock {
        val w = writer ?: throw McpException(message = "[$serverName] transport not started")
        withContext(Dispatchers.IO) {
            w.write(line)
            w.write("\n")
            w.flush()
        }
    }

    /** Read stdout line by line, routing each response by id to its pending entry; non-responses (with a method or unparseable) are ignored. */
    private fun readLoop(p: Process) {
        val reader = p.inputStream.bufferedReader()
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue

                val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                if (obj == null) {
                    // Logged at INFO without truncation: the spec reserves stdout for the protocol, so non-JSON output is usually a server startup banner or an npx interactive prompt —
                    // key evidence when diagnosing startup failures; DEBUG-only logs would be invisible in release builds (minLevel=INFO).
                    FileLogger.i(TAG, "[$serverName] skipping non-JSON line: $line")
                    continue
                }
                // A method means a server→client request/notification (e.g. sampling/roots); unsupported for now, ignore.
                if (obj.containsKey("method")) {
                    FileLogger.d(TAG, "[$serverName] ignoring server-initiated message: ${obj["method"]}")
                    continue
                }

                val resp = runCatching { json.decodeFromJsonElement(JsonRpcResponse.serializer(), obj) }.getOrNull()
                if (resp?.id == null) {
                    FileLogger.d(TAG, "[$serverName] skipping response without id")
                    continue
                }
                val deferred = pending.remove(resp.id)
                if (deferred == null) {
                    FileLogger.w(TAG, "[$serverName] received response with no matching request id=${resp.id}")
                } else {
                    deferred.complete(resp)
                }
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "[$serverName] stdout read loop ended with error: ${e.message}")
        } finally {
            FileLogger.i(TAG, "[$serverName] stdout ended (process may have exited)")
            logExitCode(p)
            failAllPending("server stdout closed")
        }
    }

    /**
     * After stdout closes the process usually exits right away; record its exit code — on install failure or abnormal termination the nonzero `exitCode` is directly visible,
     * no need to infer from stderr alone. If it has not exited yet, wait up to [EXIT_WAIT_MS]; skip if it still hasn't.
     */
    private fun logExitCode(p: Process) {
        val exited = runCatching { p.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        val code = if (exited) runCatching { p.exitValue() }.getOrNull() else null
        if (code == null) return
        if (code == 0) {
            FileLogger.i(TAG, "[$serverName] process exited with exitCode=0")
        } else {
            FileLogger.w(TAG, "[$serverName] process exited with exitCode=$code")
        }
    }

    /** The server's diagnostic logs go to stderr; drained and logged separately (npx package-fetch output lands here too — kept at INFO to help diagnose install failures). */
    private fun drainStderr(p: Process) {
        val reader = p.errorStream.bufferedReader()
        runCatching {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) FileLogger.i(TAG, "[$serverName] stderr: $line")
            }
        }
    }

    /** Fail all in-flight requests exceptionally (called on process exit / close), so callers never hang forever. */
    private fun failAllPending(reason: String) {
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.completeExceptionally(McpException(message = "[$serverName] $reason"))
        }
    }
}
