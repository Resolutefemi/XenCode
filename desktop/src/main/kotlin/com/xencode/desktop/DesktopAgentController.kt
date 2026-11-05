package com.xencode.desktop

import com.xencode.core.agent.AgentRuntime
import com.xencode.core.agent.SlashCommands
import com.xencode.core.goal.GoalDagFile
import com.xencode.core.provider.ChatClient
import com.xencode.core.provider.ProviderConfig
import com.xencode.core.state.XenEventLog
import com.xencode.core.telemetry.Telemetry
import com.xencode.core.telemetry.XpLedger
import com.xencode.core.telemetry.XpMath
import com.xencode.core.tools.AgentSession
import com.xencode.core.tools.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Per-machine XP ledger location for the desktop app: ~/.xencode/pc-xp-ledger.json,
 * the same file the CLI line uses, so both front ends feed one level.
 */
object DesktopXp {

    fun ledgerDir(): File = File(System.getProperty("user.home") ?: ".", ".xencode")

    fun ledgerPath(): String = File(ledgerDir(), "pc-xp-ledger.json").path

    fun defaultLedger(): XpLedger = XpLedger(File(ledgerPath()))

    /** +5 per successfully completed chat turn (a non-tool final round), 0 otherwise. */
    fun turnAwardFor(turnSuccessful: Boolean): Long = if (turnSuccessful) XpMath.turnAward() else 0L

    /** +50 per completed goal, 0 otherwise. */
    fun goalAwardFor(goalCompleted: Boolean): Long = if (goalCompleted) XpMath.goalAward() else 0L
}

/** One rendered row of the conversation transcript. */
sealed class ChatEntry {
    data class User(val text: String) : ChatEntry()
    data class Assistant(val text: String) : ChatEntry()
    data class ToolCall(val name: String, val argsJson: String) : ChatEntry()
    data class System(val text: String) : ChatEntry()
    data class ErrorLine(val message: String) : ChatEntry()
}

/**
 * "View model" for the Chat pane, now built on [AgentRuntime]: the shared
 * permission gate, hooks, auto-checkpoints, MCP tools, event log, session
 * autosave and the slash-command surface — the same feature set as the CLI
 * REPL, rendered as Compose state instead of stdout.
 *
 * One controller per (provider, workspace) pair is created by
 * [DesktopAppState.buildController] and disposed when either changes.
 */
class DesktopAgentController(
    val provider: ProviderConfig,
    val workspaceRoot: String,
    private val ledger: XpLedger = DesktopXp.defaultLedger(),
    client: ChatClient = ChatClient(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val ioScope: CoroutineScope = scope

    val runtime: AgentRuntime = AgentRuntime(
        workspaceRoot = File(workspaceRoot),
        provider = provider,
        client = client,
        ledgerFile = ledger.backingFile()
    )

    private val _messages = MutableStateFlow<List<ChatEntry>>(emptyList())
    val messages: StateFlow<List<ChatEntry>> = _messages.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Open decision cards (permission asks and automation prompts). */
    private val _openDecisions = MutableStateFlow<List<com.xencode.core.state.DecisionStore.Card>>(emptyList())
    val openDecisions: StateFlow<List<com.xencode.core.state.DecisionStore.Card>> = _openDecisions.asStateFlow()

    /** Current permission mode, mirrored from the workspace permissions file. */
    private val _permissionMode = MutableStateFlow(runtime.permissionMode())
    val permissionMode: StateFlow<Permissions.Mode> = _permissionMode.asStateFlow()

    private var mcpStatusNoted = false

    private val session: AgentSession = runtime.newSession(
        maxToolRounds = 16,
        listener = object : AgentSession.Listener {
            override suspend fun onToolCall(name: String, argsJson: String) {
                _messages.value = _messages.value + ChatEntry.ToolCall(name, argsJson)
            }
        }
    )

    private var sessionId: String = runtime.sessions.newId()
    private var sessionCreatedAt: Long = System.currentTimeMillis()
    private var firstPrompt: String? = null

    private var sendJob: Job? = null

    init {
        refreshDecisions()
    }

    /** Connects MCP servers once (lazily, on first send) and surfaces the status. */
    private fun ensureMcpWithStatus() {
        if (mcpStatusNoted) return
        mcpStatusNoted = true
        ioScope.launch {
            val status = runtime.ensureMcp()
            status.forEach { (name, ok) ->
                _messages.value = _messages.value + ChatEntry.System(
                    if (ok) "MCP server $name connected — its tools are available to the agent."
                    else "MCP server $name failed: ${runtime.mcp.serverErrors()[name] ?: "unknown error"}"
                )
            }
        }
    }

    /**
     * Sends one user turn. The assistant text streams into [streamingText] delta
     * by delta; tool calls requested by the model are appended to [messages] as
     * they execute. On success the session is autosaved and the turn award
     * (+5 XP) written to the ledger.
     */
    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _busy.value) return
        _error.value = null
        _messages.value = _messages.value + ChatEntry.User(prompt)
        if (firstPrompt == null) firstPrompt = prompt
        _busy.value = true
        _streamingText.value = ""
        ensureMcpWithStatus()
        sendJob = ioScope.launch {
            val streamed = StringBuilder()
            val outcome = session.send(
                userText = prompt,
                onDelta = { delta ->
                    if (delta.isNotEmpty()) {
                        streamed.append(delta)
                        _streamingText.value = streamed.toString()
                    }
                }
            )
            _streamingText.value = ""
            if (outcome != null) {
                _messages.value = _messages.value + ChatEntry.Assistant(outcome)
                awardTurnXp(turnSuccessful = true, prompt = prompt)
            } else {
                val failure = session.lastFailure ?: "The model returned an empty reply."
                _error.value = failure
                _messages.value = _messages.value + ChatEntry.ErrorLine(failure)
            }
            _busy.value = false
            persistSession()
            refreshDecisions() // a permission ask may have created a card mid-turn
        }
    }

    /**
     * Handles a slash command from the chat input (the same table as the CLI).
     * @return true when the input was consumed as a command.
     */
    fun handleSlash(text: String): Boolean {
        val parsed = SlashCommands.parse(text) ?: return false
        when (parsed.name) {
            "/help" -> system(SlashCommands.help())
            "/clear" -> { clearConversation(); return true }
            "/compact" -> compactContext()
            "/model" -> system(
                "Provider ${provider.name} (${provider.kind}) · model ${provider.model}\n" +
                    "Switch in Settings (or `xencode config use --name <name>`)."
            )
            "/system" -> system(runtime.systemPrompt())
            "/yolo" -> {
                val next = if (runtime.permissionMode() == Permissions.Mode.YOLO) Permissions.Mode.DEFAULT else Permissions.Mode.YOLO
                runtime.setPermissionMode(next)
                _permissionMode.value = next
                system("Permission mode: ${next.name.lowercase()}")
            }
            "/todo" -> system(todoOutput(parsed.arg))
            "/checkpoint" -> system(checkpointOutput(parsed.arg))
            "/dag", "/goal" -> system(GoalDagFile.load(workspaceRoot).let { dag ->
                "Goal DAG — ${dag.doneCount}/${dag.nodes.size} done (${dag.progressPercent}%)\n" +
                    dag.nodes.sortedBy { it.id }.joinToString("\n") { n ->
                        "- ${Formatters.statusGlyph(n.status)} ${n.id} ${n.title} — ${n.status}"
                    }.ifBlank { "(no goals yet)" }
            })
            "/mcp" -> {
                val servers = runtime.mcp.connectedServers()
                system(
                    if (servers.isEmpty()) "No MCP servers connected. Configure ${workspaceRoot}/.xencode/mcp.json (see `xencode mcp init`)."
                    else "Connected: ${servers.joinToString(", ")}\n" + runtime.mcp.tools().joinToString("\n") { "  ${it.prefixedName} — ${it.description.take(70)}" }
                )
            }
            "/events" -> system(
                runtime.events.latest(parsed.arg.toIntOrNull() ?: 15)
                    .joinToString("\n") { XenEventLog.pretty(it) }
                    .ifBlank { "No events yet." }
            )
            "/decide" -> { refreshDecisions(); system(decideOutput(parsed.arg)) }
            "/radar" -> {
                val t = Telemetry(File(workspaceRoot)).load()
                system(
                    Telemetry.renderRadar(t) + "\ncommand streak: ${t.commandStreak} (best ${t.bestStreak}) · " +
                        "tool calls: ${t.toolCalls} · files written: ${t.filesWritten}"
                )
            }
            "/xp" -> system(XpMath.summaryLine(ledger.summary().totalXp, ledger.summary().streakDays))
            "/session" -> system(
                "Session $sessionId (${session.historySnapshot().size} messages) — autosaved under .xencode/sessions/"
            )
            "/exit" -> { /* The desktop has no REPL to exit; treat as a no-op hint. */ }
            else -> system("Unknown command ${parsed.name} — /help lists them.")
        }
        return true
    }

    private fun todoOutput(arg: String): String {
        val parts = arg.trim().split(Regex("\\s+"), limit = 2)
        return when (parts.firstOrNull()?.lowercase() ?: "list") {
            "add" -> {
                val text = parts.getOrNull(1)?.trim().orEmpty()
                if (text.isEmpty()) "Usage: /todo add <text>"
                else com.xencode.core.tools.TodoStore.render(runtime.todo.add(text))
            }
            "done" -> {
                val needle = parts.getOrNull(1)?.trim().orEmpty()
                if (needle.isEmpty()) "Usage: /todo done <text fragment>"
                else runtime.todo.complete(needle)?.let { com.xencode.core.tools.TodoStore.render(it) }
                    ?: "No matching open item."
            }
            "clear" -> com.xencode.core.tools.TodoStore.render(runtime.todo.clear())
            else -> runtime.todo.render()
        }
    }

    private fun checkpointOutput(arg: String): String {
        val parts = arg.trim().split(Regex("\\s+"), limit = 2)
        return when (parts.firstOrNull()?.lowercase() ?: "list") {
            "create" -> {
                val cp = runtime.checkpoints.create(parts.getOrNull(1)?.trim().orEmpty().ifBlank { "manual" }, "manual checkpoint from desktop chat")
                cp?.let { "Checkpoint ${it.id} at ${it.commit} (${it.filesChanged} files)." }
                    ?: "No checkpoint: ${runtime.checkpoints.lastError}"
            }
            "restore" -> {
                val id = parts.getOrNull(1)?.trim().orEmpty()
                runtime.checkpoints.restore(id)?.let { it } ?: "Restored $id."
            }
            else -> runtime.checkpoints.list().joinToString("\n") { "${it.id} ${it.commit ?: "-"} ${it.label}: ${it.summary.take(50)}" }
                .ifBlank { "No checkpoints yet." }
        }
    }

    private fun decideOutput(arg: String): String {
        val open = runtime.decisions.open()
        if (open.isEmpty()) return "No open decision cards."
        val parts = arg.trim().split(Regex("\\s+"))
        return if (parts.firstOrNull() in setOf("approve", "reject")) {
            val id = parts.getOrNull(1) ?: open.first().id
            resolveDecision(id, parts.first()).let { "Card $id resolved." }
        } else {
            open.joinToString("\n\n") { "${it.id} — ${it.title}\n${it.body.take(140)}\noptions: ${it.options.joinToString(" | ")}" } +
                "\n\nResolve from the banner above or /decide approve|reject <id>."
        }
    }

    /** Resolves an open card (banner buttons land here). */
    fun resolveDecision(id: String, choice: String): Boolean {
        val card = runtime.decisions.resolve(id, choice) ?: return false
        runtime.events.emit(XenEventLog.Types.DECISION_RESOLVED, "$id $choice", "card" to id, "choice" to choice)
        refreshDecisions()
        system("Decision $id resolved: $choice — re-run the step to continue.")
        return true
    }

    private fun refreshDecisions() {
        _openDecisions.value = runtime.decisions.open()
    }

    /** Deterministic context compaction (the /compact command). */
    fun compactContext() {
        if (_busy.value) { system("Cannot compact while a turn is streaming."); return }
        val saved = session.compactHistory()
        runtime.events.emit(XenEventLog.Types.COMPACTED, "saved $saved chars")
        system(if (saved > 0) "Compacted: $saved characters of context dropped." else "Nothing to compact yet.")
    }

    /** Stops the in-flight turn; the transcript stays so the user can simply re-ask. */
    fun cancelTurn() {
        sendJob?.cancel()
        sendJob = null
        _streamingText.value = ""
        _busy.value = false
        _error.value = "Turn cancelled"
    }

    /** Drops the whole conversation (both the transcript and the model-side history). */
    fun clearConversation() {
        if (_busy.value) cancelTurn()
        session.clear()
        _messages.value = emptyList()
        _error.value = null
    }

    fun dispose() {
        sendJob?.cancel()
        runCatching { persistSession() }
        runtime.mcp.disconnect()
        ioScope.cancel()
    }

    private fun system(text: String) {
        _messages.value = _messages.value + ChatEntry.System(text)
    }

    private fun persistSession() {
        runCatching {
            runtime.sessions.save(
                id = sessionId,
                title = runtime.sessions.titleFrom(firstPrompt ?: "session"),
                provider = provider.name,
                model = provider.model,
                createdAt = sessionCreatedAt,
                messages = session.historySnapshot()
            )
        }
    }

    private fun awardTurnXp(turnSuccessful: Boolean, prompt: String) {
        if (DesktopXp.turnAwardFor(turnSuccessful) <= 0L) return
        runCatching {
            ledger.awardTurn(prompt.take(50).ifBlank { "chat turn" })
        }
    }
}
