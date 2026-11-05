package com.xencode.core.mcp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One stdio MCP server: newline-delimited JSON-RPC 2.0 over the child process
 * stdin/stdout. The protocol surface used here is initialize / notifications/
 * initialized / tools/list / tools/call (MCP spec 2024-11-05), which covers the
 * local-server majority of the ecosystem.
 *
 * All calls are serialized behind one lock and block with a timeout — safe to
 * call from the agent tool loop on any thread.
 */
class McpStdioServer(
    val name: String,
    private val command: List<String>,
    private val env: Map<String, String> = emptyMap(),
    private val cwd: File? = null,
    private val requestTimeoutMs: Long = 20_000
) {
    var lastError: String? = null
        private set

    private val gson = Gson()
    private val lock = ReentrantLock()
    private val nextId = AtomicLong(0)

    private var process: Process? = null
    private var stdin: PrintWriter? = null
    private var stdout: BufferedReader? = null

    val running: Boolean get() = process?.isAlive == true

    /** Spawns the process and performs the initialize handshake. */
    fun start(): Boolean = lock.withLock {
        if (running) return true
        lastError = null
        return try {
            val proc = ProcessBuilder(command)
                .apply {
                    cwd?.let { directory(it) }
                    env.forEach { (k, v) -> environment()[k] = v }
                }
                .start()
            process = proc
            stdin = PrintWriter(proc.outputStream, true)
            stdout = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
            val init = request(
                "initialize",
                mapOf(
                    "protocolVersion" to "2024-11-05",
                    "capabilities" to emptyMap<String, Any>(),
                    "clientInfo" to mapOf("name" to "xencode", "version" to "1.1.0")
                )
            )
            if (init == null) {
                val reason = lastError ?: "no response"
                stopLocked()
                lastError = "initialize failed: $reason"
                false
            } else {
                notify("notifications/initialized")
                true
            }
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
            false
        }
    }

    /** Lists the tools the server exposes (raw JSON objects). */
    fun listTools(): List<JsonObject> {
        val result = request("tools/list", emptyMap<String, Any>()) ?: return emptyList()
        return result.getAsJsonArray("tools")?.mapNotNull { el ->
            el.takeIf { it.isJsonObject }?.asJsonObject
        } ?: emptyList()
    }

    /** Calls a tool; returns the concatenated text content or "ERROR: ...". */
    fun callTool(toolName: String, argsJson: String): String {
        val arguments = runCatching {
            JsonParser.parseString(argsJson.ifBlank { "{}" }).takeIf { it.isJsonObject }?.asJsonObject
                ?: JsonObject()
        }.getOrNull() ?: JsonObject()
        val result = request("tools/call", mapOf("name" to toolName, "arguments" to arguments))
            ?: return "ERROR: MCP server `$name` did not answer tools/call ${lastError?.let { "($it)" } ?: ""}"
        val isError = result.get("isError")?.takeIf { !it.isJsonNull }?.asBoolean == true
        val text = result.getAsJsonArray("content")
            ?.mapNotNull { el -> el.asJsonObject?.get("text")?.takeIf { !it.isJsonNull }?.asString }
            ?.joinToString("\n")
            ?: gson.toJson(result)
        return if (isError) "ERROR: $text" else text
    }

    /** Sends a request and waits for the response with the matching id. */
    private fun request(method: String, params: Any): JsonObject? {
        val proc = process ?: run { lastError = "server not started"; return null }
        val id = nextId.incrementAndGet()
        val payload = linkedMapOf<String, Any?>("jsonrpc" to "2.0", "id" to id, "method" to method)
        if (params is Map<*, *> && params.isNotEmpty()) payload["params"] = params
        stdin?.write(gson.toJson(payload) + "\n")
        stdin?.flush()
        val deadline = System.currentTimeMillis() + requestTimeoutMs
        // Watchdog: a hung server must not block the agent loop forever — killing
        // the process unblocks the reader with an EOF/IOException.
        val watchdog = java.util.Timer("mcp-watchdog", true)
        watchdog.schedule(object : java.util.TimerTask() {
            override fun run() { if (proc.isAlive) proc.destroy() }
        }, requestTimeoutMs)
        try {
            while (System.currentTimeMillis() < deadline && proc.isAlive) {
                val line = readLineWithDeadline(deadline) ?: break
                val obj = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                if (obj.get("id")?.takeIf { !it.isJsonNull }?.asLong == id) {
                    val error = obj.get("error")?.takeIf { !it.isJsonNull }?.asJsonObject
                    if (error != null) {
                        lastError = error.get("message")?.asString ?: "json-rpc error"
                        return null
                    }
                    return obj.get("result")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                }
                // notifications / server logs are dropped
            }
        } finally {
            watchdog.cancel()
        }
        lastError = if (proc.isAlive) "timeout after ${requestTimeoutMs}ms" else "server exited"
        return null
    }

    /** Blocking line read bounded by the deadline (reads char-by-char). */
    private fun readLineWithDeadline(deadline: Long): String? {
        val reader = stdout ?: return null
        val sb = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            val c = reader.read() // blocks; process death makes it throw/return -1
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        return null
    }

    private fun notify(method: String) {
        val payload = mapOf("jsonrpc" to "2.0", "method" to method)
        stdin?.write(gson.toJson(payload) + "\n")
        stdin?.flush()
    }

    private fun stopLocked() {
        runCatching { stdin?.close() }
        runCatching { stdout?.close() }
        runCatching { process?.destroyForcibly() }
        process = null
        stdin = null
        stdout = null
    }

    fun close() = lock.withLock { stopLocked() }
}

/** One tool offered by an MCP server, already flattened for the agent loop. */
data class McpTool(
    val server: String,
    val name: String,
    val description: String,
    val inputSchemaJson: String
) {
    /** The name the agent loop uses; `mcp_` prefix keeps them apart from local tools. */
    val prefixedName: String get() = "mcp_${server}_${name}"
}

/**
 * Registry over `.xencode/mcp.json`:
 * `{"servers": {"<name>": {"command": "...", "args": [...], "env": {...}}}}`
 *
 * Connects all configured servers, exposes their tools through the standard
 * [com.xencode.core.tools.ToolExecutor] surface and routes calls back.
 */
class McpRegistry(private val workspaceRoot: File) {

    data class ServerConfig(val command: String, val args: List<String> = emptyList(), val env: Map<String, String> = emptyMap())

    private val configGson = GsonBuilder().setPrettyPrinting().create()
    private val configFile: File get() = File(File(workspaceRoot, ".xencode"), "mcp.json")

    private val servers = LinkedHashMap<String, McpStdioServer>()
    private val tools = ArrayList<McpTool>()
    private val connectErrors = LinkedHashMap<String, String>()

    fun loadConfig(): Map<String, ServerConfig> = runCatching {
        val obj = JsonParser.parseString(configFile.readText(Charsets.UTF_8)).asJsonObject
        val serversObj = obj.getAsJsonObject("servers") ?: return emptyMap()
        serversObj.keySet().associateWith { key ->
            val s = serversObj.getAsJsonObject(key)
            ServerConfig(
                command = s.get("command")?.asString ?: "",
                args = s.getAsJsonArray("args")?.map { it.asString } ?: emptyList(),
                env = s.getAsJsonObject("env")?.entrySet()?.associate { it.key to it.value.asString } ?: emptyMap()
            )
        }.filterValues { it.command.isNotBlank() }
    }.getOrElse { emptyMap() }

    fun saveConfig(config: Map<String, ServerConfig>) {
        configFile.parentFile?.mkdirs()
        configFile.writeText(configGson.toJson(mapOf("servers" to config)), Charsets.UTF_8)
    }

    /** Connects every configured server; returns one status line per server. */
    fun connectAll(): List<Pair<String, Boolean>> {
        disconnect()
        connectErrors.clear()
        return loadConfig().map { (name, cfg) ->
            val server = McpStdioServer(name, listOf(cfg.command) + cfg.args, cfg.env, workspaceRoot)
            val ok = server.start()
            if (ok) {
                servers[name] = server
                server.listTools().forEach { t ->
                    val toolName = t.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                    tools.add(
                        McpTool(
                            server = name,
                            name = toolName,
                            description = t.get("description")?.takeIf { !it.isJsonNull }?.asString ?: "MCP tool from $name",
                            inputSchemaJson = t.get("inputSchema")?.toString()
                                ?: """{"type":"object","properties":{}}"""
                        )
                    )
                }
            } else {
                connectErrors[name] = server.lastError ?: "failed to start"
            }
            name to ok
        }
    }

    fun connectedServers(): List<String> = servers.keys.toList()

    /** Errors from the last connectAll(): failed servers first, then live servers. */
    fun serverErrors(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out.putAll(connectErrors)
        servers.forEach { (name, s) -> s.lastError?.let { out[name] = it } }
        return out.filterValues { it.isNotBlank() }
    }

    fun tools(): List<McpTool> = tools.toList()

    fun callTool(prefixedName: String, argsJson: String): String {
        val tool = tools.firstOrNull { it.prefixedName == prefixedName }
            ?: return "ERROR: unknown MCP tool `$prefixedName`"
        val server = servers[tool.server]
            ?: return "ERROR: MCP server `${tool.server}` is not connected"
        return server.callTool(tool.name, argsJson)
    }

    fun disconnect() {
        servers.values.forEach { it.close() }
        servers.clear()
        tools.clear()
    }
}
