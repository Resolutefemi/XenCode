package com.xencode.core.agent

import com.xencode.core.mcp.McpRegistry
import com.xencode.core.prompt.SystemPromptBuilder
import com.xencode.core.provider.ChatClient
import com.xencode.core.provider.ChatTransport
import com.xencode.core.provider.ProviderConfig
import com.xencode.core.state.CheckpointStore
import com.xencode.core.state.DecisionStore
import com.xencode.core.state.SessionStore
import com.xencode.core.state.XenEventLog
import com.xencode.core.telemetry.Telemetry
import com.xencode.core.telemetry.XpLedger
import com.xencode.core.telemetry.XpStore
import com.xencode.core.tools.AgentSession
import com.xencode.core.tools.HookStore
import com.xencode.core.tools.LocalTools
import com.xencode.core.tools.PermissionGates
import com.xencode.core.tools.PermissionStore
import com.xencode.core.tools.Permissions
import com.xencode.core.tools.TodoStore
import com.xencode.core.tools.ToolExecutor
import com.xencode.core.tools.ToolSpec
import com.google.gson.JsonParser
import java.io.File

/**
 * One AgentRuntime per (workspace, provider): the bundle of PC-line state and
 * the permission/hook/checkpoint-gated tool executor shared by the CLI and the
 * desktop app.
 *
 * Everything under `.xencode/` in the workspace is treated as the PC database
 * (sessions, checkpoints, decisions, events, telemetry, todo, permissions,
 * hooks, MCP config) — the same home the Android container runtime uses, so a
 * future sync layer has exactly one root per workspace.
 */
class AgentRuntime(
    val workspaceRoot: File,
    val provider: ProviderConfig,
    private val client: ChatTransport = ChatClient(),
    val autoCheckpoint: Boolean = true,
    /**
     * XP sink override. Null (the CLI default) keeps the per-machine JSON ledger;
     * the desktop passes the shared-database ledger (DbXpLedger) so goal awards
     * land in the same `xp_events` table its chat turns do.
     */
    xpStore: XpStore? = null
) {
    val events = XenEventLog(workspaceRoot)
    val decisions = DecisionStore(workspaceRoot)
    val checkpoints = CheckpointStore(workspaceRoot)
    val telemetry = Telemetry(workspaceRoot, events)
    val sessions = SessionStore(workspaceRoot)
    val todo = TodoStore(workspaceRoot)
    val permissions = PermissionStore(workspaceRoot)
    val hooks = HookStore(workspaceRoot)
    val mcp = McpRegistry(workspaceRoot)
    val local = LocalTools(workspaceRoot)

    /** XP sink: the injected [xpStore], or the per-machine JSON ledger (~/.xencode/pc-xp-ledger.json). */
    val ledger: XpStore = xpStore
        ?: XpLedger(File(File(System.getProperty("user.home") ?: ".", ".xencode"), "pc-xp-ledger.json"))

    private var mcpConnected = false

    /** Connects configured MCP servers once; later calls are no-ops. */
    fun ensureMcp(force: Boolean = false): List<Pair<String, Boolean>> {
        if (mcpConnected && !force) return emptyList()
        mcpConnected = true
        val status = mcp.connectAll()
        status.forEach { (name, ok) ->
            events.emit(XenEventLog.Types.MCP_STATUS, "$name ${if (ok) "connected" else "failed"}", "server" to name, "ok" to ok)
        }
        return status
    }

    fun permissionMode(): Permissions.Mode = permissions.mode()

    fun setPermissionMode(mode: Permissions.Mode) {
        val cfg = permissions.load()
        permissions.save(cfg.copy(mode = mode.name.lowercase()))
        events.emit(XenEventLog.Types.STATE_SYNC, "permission mode -> ${mode.name.lowercase()}")
    }

    fun systemPrompt(): String {
        val names = buildList {
            addAll(local.schemas().map { it.name })
            if (mcpConnected) addAll(mcp.tools().map { it.prefixedName })
        }
        return SystemPromptBuilder.build(workspaceRoot.absolutePath, names)
    }

    fun newSession(maxToolRounds: Int = 12, listener: AgentSession.Listener? = null): AgentSession =
        AgentSession(
            client = client,
            config = provider,
            tools = gatedTools(),
            systemPrompt = systemPrompt(),
            maxToolRounds = maxToolRounds,
            listener = listener
        )

    /**
     * The composite executor: local tools + MCP tools behind the permission
     * gate, with pre/post hooks, telemetry, auto-checkpoints and event emission.
     */
    inner class GatedTools : ToolExecutor {

        private val writeTools = setOf("write_file", "edit_file", "delete_file")

        override fun schemas(): List<ToolSpec> =
            local.schemas() + if (mcpConnected) {
                mcp.tools().map { ToolSpec(it.prefixedName, "[${it.server}] ${it.description}", it.inputSchemaJson) }
            } else emptyList()

        override fun execute(name: String, argsJson: String): String {
            events.emit(XenEventLog.Types.TOOL_CALL, PermissionGates.describe(name, argsJson), "tool" to name)

            // 1. Permission gate.
            when (val d = permissions.check(name, argsJson)) {
                is Permissions.Decision.Deny -> {
                    events.emit(XenEventLog.Types.TOOL_RESULT, "denied: ${d.reason}", "tool" to name)
                    return "ERROR: denied by permission policy: ${d.reason}"
                }
                is Permissions.Decision.Ask -> {
                    val card = decisions.create(
                        title = "Approve ${PermissionGates.describe(name, argsJson)}",
                        body = "Reason: ${d.reason}. Approving re-runs the step; rejecting keeps it blocked.",
                        options = listOf("approve", "reject")
                    )
                    events.emit(XenEventLog.Types.DECISION_REQUESTED, card.title, "card" to card.id)
                    return "ERROR: approval required (decision card ${card.id} created: ${d.reason}). " +
                        "Stop this turn and tell the user to resolve it with `xencode decide approve|reject ${card.id}`, then retry."
                }
                Permissions.Decision.Allow -> Unit
            }

            // 2. Pre-tool hooks (a non-zero exit blocks).
            hooks.runner().runPre(name, argsJson)?.let {
                events.emit(XenEventLog.Types.TOOL_RESULT, "blocked by hook", "tool" to name)
                return "ERROR: blocked by pre-tool hook: $it"
            }

            // 3. Auto-checkpoint before the first risky write.
            if (autoCheckpoint && name in writeTools) {
                checkpoints.create("pre-edit", PermissionGates.describe(name, argsJson))?.let {
                    events.emit(XenEventLog.Types.CHECKPOINT, "${it.id} ${it.commit ?: "-"}", "label" to "pre-edit")
                }
            }

            telemetry.recordToolCall()

            // 4. Execute (local or MCP).
            val result = if (name.startsWith("mcp_")) {
                if (!mcpConnected) "ERROR: MCP is not connected (run `xencode mcp list`)"
                else mcp.callTool(name, argsJson)
            } else {
                local.execute(name, argsJson)
            }

            // 5. Telemetry: command streaks + test radar.
            if (name == "run_command") {
                val cmd = runCatching {
                    JsonParser.parseString(argsJson.ifBlank { "{}" }).asJsonObject.get("command")?.asString
                }.getOrNull() ?: ""
                val exit = Regex("exit=(-?\\d+)").find(result)?.groupValues?.get(1)?.toInt() ?: 1
                telemetry.recordCommand(cmd, exit)
            }
            if (name in writeTools && !result.startsWith("ERROR")) telemetry.recordFileWrite()

            // 6. Post-tool hooks.
            val post = hooks.runner().runPost(name, argsJson, result)?.let { "\n[hook] $it" } ?: ""

            events.emit(
                XenEventLog.Types.TOOL_RESULT,
                (if (result.startsWith("ERROR")) "failed" else "ok") + " " + name,
                "tool" to name,
                "chars" to result.length
            )
            return result + post
        }
    }

    private val gated by lazy { GatedTools() }

    fun gatedTools(): ToolExecutor = gated
}
