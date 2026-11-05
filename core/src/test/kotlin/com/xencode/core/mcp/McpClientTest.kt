package com.xencode.core.mcp

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.io.File

/**
 * MCP stdio client against a scripted `/bin/sh` JSON-RPC server (the same
 * shape a real stdio MCP server speaks). Skipped on hosts without /bin/sh.
 */
class McpClientTest {

    @TempDir
    lateinit var tmp: File

    private fun shellAvailable(): Boolean = File("/bin/sh").canExecute()

    /** A tiny POSIX-sh MCP server: initialize -> tools/list (echo) -> tools/call. */
    private fun writeEchoServer(): File {
        val f = File(tmp, "echo-server.sh")
        f.writeText(
            """
            #!/bin/sh
            while IFS= read -r line; do
              id=$(printf '%s' "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
              method=$(printf '%s' "${'$'}line" | sed -n 's/.*"method":"\([^"]*\)".*/\1/p')
              case "${'$'}method" in
                initialize)
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"echo","version":"1.0"}}}\n' "${'$'}id"
                  ;;
                tools/list)
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"echo","description":"echoes text","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}]}}\n' "${'$'}id"
                  ;;
                tools/call)
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"echo says hi"}]}}\n' "${'$'}id"
                  ;;
              esac
            done
            """.trimIndent()
        )
        f.setExecutable(true)
        return f
    }

    @Test
    fun stdioServer_initializeListToolsAndCall() {
        assumeTrue(shellAvailable(), "/bin/sh not available")
        val server = McpStdioServer("echo", listOf(writeEchoServer().absolutePath), cwd = tmp, requestTimeoutMs = 10_000)
        assertTrue(server.start(), server.lastError)

        val tools = server.listTools()
        assertEquals(1, tools.size)
        assertEquals("echo", tools[0].get("name").asString)

        val result = server.callTool("echo", """{"text":"hello"}""")
        assertTrue(result.contains("echo says hi"), result)
        server.close()
    }

    @Test
    fun registry_configSaveLoadAndToolRouting() {
        val ws = File(tmp, "ws").also { it.mkdirs() }
        val registry = McpRegistry(ws)
        assertTrue(registry.loadConfig().isEmpty())
        registry.saveConfig(mapOf("echo" to McpRegistry.ServerConfig(command = writeEchoServer().absolutePath)))
        assertEquals(1, registry.loadConfig().size)
        assertEquals("echo", registry.loadConfig().keys.single())

        assumeTrue(shellAvailable(), "/bin/sh not available")
        val status = registry.connectAll()
        assertEquals(listOf("echo" to true), status)
        assertEquals(1, registry.tools().size)
        assertEquals("mcp_echo_echo", registry.tools().single().prefixedName)

        val call = registry.callTool("mcp_echo_echo", """{"text":"x"}""")
        assertTrue(call.contains("echo says hi"), call)
        assertTrue(registry.callTool("mcp_unknown_x", "{}").startsWith("ERROR"))
        registry.disconnect()
    }

    @Test
    fun registry_badServerReportsFailure() {
        assumeTrue(shellAvailable(), "/bin/sh not available")
        val ws = File(tmp, "ws2").also { it.mkdirs() }
        val registry = McpRegistry(ws)
        registry.saveConfig(mapOf("broken" to McpRegistry.ServerConfig(command = "/nonexistent/binary")))
        val status = registry.connectAll()
        assertEquals(listOf("broken" to false), status)
        assertTrue(registry.serverErrors().getValue("broken").isNotEmpty())
    }

    @Test
    fun registry_configParsesArgsAndEnv() {
        val ws = File(tmp, "ws3").also { it.mkdirs() }
        File(File(ws, ".xencode"), "mcp.json").apply {
            parentFile.mkdirs()
            writeText(
                """
                {"servers": {"srv": {"command": "node", "args": ["-y", "mcp"], "env": {"KEY": "V"}}}}
                """.trimIndent()
            )
        }
        val cfg = McpRegistry(ws).loadConfig()
        assertEquals("node", cfg["srv"]!!.command)
        assertEquals(listOf("-y", "mcp"), cfg["srv"]!!.args)
        assertEquals(mapOf("KEY" to "V"), cfg["srv"]!!.env)
        // sanity: the config JSON parses as valid JSON
        JsonParser.parseString(File(File(ws, ".xencode"), "mcp.json").readText())
    }
}
