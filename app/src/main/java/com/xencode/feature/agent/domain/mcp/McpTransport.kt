package com.xencode.feature.agent.domain.mcp

import kotlinx.serialization.json.JsonObject

/**
 * MCP transport abstraction: delivers one JSON-RPC request to the server and retrieves a single response.
 *
 * Deliberately pluggable: remote goes through [StreamableHttpTransport], in-container subprocesses through [StdioTransport];
 * [McpClient] is unaware of the concrete transport.
 */
interface McpTransport {
    /**
     * Send a request expecting a reply, suspending until the paired [JsonRpcResponse] arrives.
     * Transport/protocol errors are thrown as [McpException].
     */
    suspend fun request(method: String, params: JsonObject? = null): JsonRpcResponse

    /** Send a notification that expects no reply (fire-and-forget). */
    suspend fun notify(method: String, params: JsonObject? = null)

    /** Release underlying resources (connections, sessions, etc.). Idempotent. */
    fun close()
}
