package com.aicode.feature.agent.domain.tool.mcp

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpScope
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

class ManageMcpTool @Inject constructor(
    private val mcpConfigRepository: McpConfigRepository
) : AgentTool() {
    private companion object {
        const val TAG = "ManageMcpTool"
    }

    override val name = "manageMcp"
    override val description = "Manage Model Context Protocol (MCP) servers: add, remove, and list. Add/remove changes Agent config and does not install runtimes such as Node/Python."
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_AGENT_CONFIG)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return when (args["action"]?.jsonPrimitive?.contentOrNull) {
            "list" -> setOf(ToolCapability.READ_AGENT_CONFIG)
            else -> capabilities
        }
    }

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "Action: list=list servers; add_stdio=add a local stdio server; add_http=add a remote HTTP server; remove=remove a server",
            enum = listOf("list", "add_stdio", "add_http", "remove")
        ),
        "server_name" to ToolParameter(
            name = "server_name",
            type = ParameterType.STRING,
            description = "MCP server name (required except for list). ASCII letters, digits, underscores, and hyphens only",
            required = false
        ),
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "Command to run, e.g. npx or python (required for add_stdio)",
            required = false
        ),
        "args" to ToolParameter(
            name = "args",
            type = ParameterType.ARRAY,
            description = "Command argument array (optional for add_stdio)",
            required = false,
            itemsSchema = mapOf("type" to "string")
        ),
        "url" to ToolParameter(
            name = "url",
            type = ParameterType.STRING,
            description = "Remote HTTP server URL (required for add_http)",
            required = false
        ),
        "scope" to ToolParameter(
            name = "scope",
            type = ParameterType.STRING,
            description = "Scope: global (default) or project (this workspace only; project overrides global)",
            enum = listOf("global", "project"),
            required = false
        )
    )

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val server = args["server_name"]?.jsonPrimitive?.contentOrNull ?: "unspecified"
        val scope = resolveScope(args)
        val scopeLabel = if (scope == McpScope.PROJECT) "this project" else "global"
        val summary = when (action) {
            "add_stdio" -> "Add local MCP server: $server ($scopeLabel)"
            "add_http" -> "Add HTTP MCP server: $server ($scopeLabel)"
            "remove" -> "Remove MCP server: $server ($scopeLabel)"
            "list" -> "List MCP servers ($scopeLabel)"
            else -> "Manage MCP servers"
        }
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "Confirm MCP config change",
            summary = summary,
            details = "Action: $action\nServer: $server\nScope: $scopeLabel\nThis changes Agent MCP config. New servers take effect on the next session.",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("Missing required parameter: action")
        val scope = resolveScope(args)
        val scopeLabel = if (scope == McpScope.PROJECT) "this project" else "global"

        suspend fun readServers(): List<McpServerConfig> =
            if (scope == McpScope.PROJECT) mcpConfigRepository.getProjectServers() else mcpConfigRepository.getGlobalServers()

        suspend fun writeServers(servers: List<McpServerConfig>) {
            if (scope == McpScope.PROJECT) mcpConfigRepository.setProjectServers(servers) else mcpConfigRepository.setGlobalServers(servers)
        }

        return try {
            when (action) {
                "list" -> {
                    val servers = readServers()
                    ToolResult.Success(JsonPrimitive(mcpConfigRepository.serialize(servers)))
                }
                "remove" -> {
                    val name = args["server_name"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("remove requires server_name")
                    val servers = readServers().toMutableList()
                    val removed = servers.removeIf { it.name == name }
                    if (removed) {
                        writeServers(servers)
                        ToolResult.Success(JsonPrimitive("Removed $scopeLabel MCP server: $name"))
                    } else {
                        ToolResult.Error("No $scopeLabel MCP server named $name")
                    }
                }
                "add_stdio" -> {
                    val name = args["server_name"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_stdio requires server_name")
                    if (!McpServerConfig.isValidName(name)) return ToolResult.Error(invalidNameMessage(name))
                    val command = args["command"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_stdio requires command")
                    val commandArgs = args["args"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    
                    val newServer = McpServerConfig(
                        name = name,
                        command = command,
                        args = commandArgs,
                        env = emptyMap(),
                        enabled = true
                    )
                    
                    val servers = readServers().toMutableList()
                    servers.removeIf { it.name == name }
                    servers.add(newServer)
                    writeServers(servers)
                    
                    ToolResult.Success(JsonPrimitive("Added $scopeLabel local MCP server: $name. Config takes effect on the next session. If the command needs Node/Python or similar, install it with a command tool after the user confirms."))
                }
                "add_http" -> {
                    val name = args["server_name"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_http requires server_name")
                    if (!McpServerConfig.isValidName(name)) return ToolResult.Error(invalidNameMessage(name))
                    val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_http requires url")
                    
                    val newServer = McpServerConfig(
                        name = name,
                        url = url,
                        headers = emptyMap(),
                        enabled = true
                    )
                    
                    val servers = readServers().toMutableList()
                    servers.removeIf { it.name == name }
                    servers.add(newServer)
                    writeServers(servers)
                    
                    ToolResult.Success(JsonPrimitive("Added $scopeLabel HTTP MCP server: $name. Config takes effect on the next session."))
                }
                else -> ToolResult.Error("Unknown action: $action")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "manage_mcp 执行失败: ${e.message}", e)
            ToolResult.Error("Failed to manage MCP: ${e.message}")
        }
    }

    private fun invalidNameMessage(name: String): String =
        "server_name may contain only ASCII letters, digits, underscores, and hyphens (got: $name). The name becomes mcp__<server_name>__<tool> for the model and must follow tool naming rules."

    private fun resolveScope(args: Map<String, JsonElement>): McpScope =
        when (args["scope"]?.jsonPrimitive?.contentOrNull) {
            "project", "PROJECT" -> McpScope.PROJECT
            else -> McpScope.GLOBAL
        }
    
}
