package com.xencode.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.xencode.core.mcp.McpRegistry
import com.xencode.core.state.XenEventLog
import java.io.File

/**
 * `xencode mcp` — manage Model Context Protocol servers (stdio transport),
 * configured in `<workspace>/.xencode/mcp.json`:
 *
 * `{"servers": {"name": {"command": "...", "args": [...], "env": {...}}}}`
 *
 * - `mcp list`                        configured servers + connect status
 * - `mcp tools`                       tools the connected servers expose
 * - `mcp call <mcp_server_tool> <jsonArgs>`   one direct call
 * - `mcp init`                        write an example config (if none exists)
 */
class McpCommand : XeCommand(
    name = "mcp",
    helpText = "MCP (Model Context Protocol) stdio servers: list / tools / call / init"
) {
    private val args by argument("args").multiple()
    private val runtime by requireObject<CliRuntime>()

    override fun run() {
        val registry = McpRegistry(File(runtime.workspaceRoot))
        when (val verb = args.firstOrNull()?.lowercase() ?: "list") {
            "list", "ls", "" -> {
                val config = registry.loadConfig()
                if (config.isEmpty()) {
                    echo("No MCP servers configured. `xencode mcp init` writes an example to .xencode/mcp.json.")
                    return
                }
                val status = registry.connectAll()
                status.forEach { (name, ok) ->
                    val err = registry.serverErrors()[name]
                    echo(if (ok) "[ok] $name — ${registry.tools().count { it.server == name }} tools" else "[fail] $name — $err", err = !ok)
                }
                registry.disconnect()
            }

            "tools" -> {
                val status = registry.connectAll()
                val failed = status.count { !it.second }
                if (failed > 0) echo("$failed server(s) failed to connect (see `xencode mcp list`).", err = true)
                val tools = registry.tools()
                if (tools.isEmpty()) echo("No tools exposed by the configured servers.")
                tools.forEach { echo("${it.prefixedName.padEnd(36)} ${it.description.take(70)}") }
                registry.disconnect()
            }

            "call" -> {
                val tool = args.getOrNull(1) ?: throw UsageError("Usage: xencode mcp call <mcp_server_tool> '<json args>'")
                val json = args.drop(2).joinToString(" ").ifBlank { "{}" }
                registry.connectAll().firstOrNull { !it.second }?.let {
                    echo("A server failed to connect — see `xencode mcp list`.", err = true)
                }
                val result = registry.callTool(tool, json)
                echo(result)
                registry.disconnect()
                if (result.startsWith("ERROR")) throw ProgramResult(1)
            }

            "init" -> {
                if (registry.loadConfig().isNotEmpty()) {
                    echo(".xencode/mcp.json already exists (left untouched).")
                    return
                }
                registry.saveConfig(
                    mapOf(
                        "example" to McpRegistry.ServerConfig(
                            command = "npx",
                            args = listOf("-y", "@modelcontextprotocol/server-filesystem", "."),
                            env = emptyMap()
                        )
                    )
                )
                echo("Wrote an example server to ${runtime.workspaceRoot}/.xencode/mcp.json — edit it, then `xencode mcp list`.")
            }

            else -> throw UsageError("Unknown subcommand `$verb` (list | tools | call | init)")
        }
    }
}
