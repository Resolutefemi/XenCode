package com.xencode.desktop

import com.xencode.core.agent.AgentRuntime
import com.xencode.core.agent.SlashCommands
import com.xencode.core.db.DbXpLedger
import com.xencode.core.db.SharedChatStore
import com.xencode.core.db.SharedDb
import com.xencode.core.goal.GoalDagFile
import com.xencode.core.provider.ChatClient
import com.xencode.core.provider.ChatTransport
import com.xencode.core.provider.ProviderConfig
import com.xencode.core.state.XenEventLog
import com.xencode.core.telemetry.Telemetry
import com.xencode.core.telemetry.XpMath
import com.xencode.core.telemetry.XpStore
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
 * XP + persistence home for the desktop front end.
 *
 * XP: the shared database is the source of truth (xp_events in
 * `aicode_agent_db`, the same table the Android app writes through Room); the
 * old per-machine JSON ledger (`~/.xencode/pc-xp-ledger.json`) is imported
 * once so switching to the DB never loses a level or streak. The JSON file is
 * still written by the CLI, which keeps feeding the same curve.
 */
object DesktopXp {

    fun ledgerDir(): File = File(System.getProperty("user.home") ?: ".", ".xencode")

    fun ledgerPath(): String = File(ledgerDir(), "pc-xp-ledger.json").path

    fun defaultLedger(): com.xencode.core.telemetry.XpLedger = com.xencode.core.telemetry.XpLedger(File(ledgerPath()))

    /** `~/.xencode/aicode_agent_db` — the shared database path on this machine. */
    fun dbPath(): String = SharedDb.defaultPath()

    @Volatile
    private var sharedDb: SharedDb? = null

    /** Process-wide shared database connection (never closed; lives with the app). */
    fun sharedDb(): SharedDb {
        sharedDb?.let { return it }
        synchronized(this) {
            sharedDb?.let { return it }
            return SharedDb.open(SharedDb.defaultPath()).also { sharedDb = it }
        }
    }

    /** The XP ledger backed by the shared database, with one-time legacy JSON import. */
    fun defaultXpStore(): XpStore {
        val ledger = DbXpLedger(sharedDb())
        runCatching { ledger.importLegacyJson(File(ledgerPath())) }
        return ledger
    }

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
 * Every turn is persisted into the shared database (`aicode_agent_db`, the
 * Kotlin app's DB contract): a `chat_sessions` row per conversation, USER /
 * ASSISTANT / TOOL rows in `agent_messages`, one `llm_call_records` row per
 * turn (tokens, duration, status) and the XP award in `xp_events`. The
 * model-side history stays in `.xencode/sessions/` (the resume format).
 *
 * One controller per (provider, workspace) pair is created by
 * [DesktopAppState.buildController] and disposed when either changes.
 */
class DesktopAgentController(
    val provider: ProviderConfig,
    val workspaceRoot: String,
    private val db: SharedDb = DesktopXp.sharedDb(),
    client: ChatTransport = ChatClient(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val ioScope: CoroutineScope = scope
    private val chatStore = SharedChatStore(db)

    /** The XP ledger in the shared database; goal awards (GoalRunner) land here too. */
    private val xp: DbXpLedger = DbXpLedger(db)

    val runtime: AgentRuntime = AgentRuntime(
        workspaceRoot = File(workspaceRoot),
        provider = provider,
        client = client,
        xpStore = xp
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

            override suspend fun onToolResult(name: String, argsJson: String, result: String) {
                recordToolRow(name, argsJson, result)
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
        recordUserRow(prompt)
        sendJob = ioScope.launch {
            val startedAt = System.currentTimeMillis()
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
            val durationMillis = (System.currentTimeMillis() - startedAt).toInt()
            _streamingText.value = ""
            if (outcome != null) {
                _messages.value = _messages.value + ChatEntry.Assistant(outcome)
                recordAssistantRow(outcome)
                recordLlmCall(status = "success", durationMillis = durationMillis, errorMessage = null)
                addSessionTokenTotals()
                awardTurnXp(turnSuccessful = true, prompt = prompt)
            } else {
                val failure = session.lastFailure ?: "The model returned an empty reply."
                _error.value = failure
                _messages.value = _messages.value + ChatEntry.ErrorLine(failure)
                recordLlmCall(status = "error", durationMillis = durationMillis, errorMessage = failure)
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
            "/xp" -> {
                val s = xp.summary()
                system(XpMath.summaryLine(s.totalXp, s.streakDays))
            }
            "/costs" -> system(costsOutput())
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

    private fun recordUserRow(prompt: String) {
        runCatching {
            upsertDbSession()
            chatStore.appendMessage(
                SharedChatStore.MessageRow(
                    id = chatStore.newMessageId(),
                    sessionId = sessionId,
                    role = "USER",
                    content = prompt,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    private fun recordAssistantRow(text: String) {
        runCatching {
            val usage = session.lastTurnUsage
            chatStore.appendMessage(
                SharedChatStore.MessageRow(
                    id = chatStore.newMessageId(),
                    sessionId = sessionId,
                    role = "ASSISTANT",
                    content = text,
                    timestamp = System.currentTimeMillis(),
                    inputTokens = usage?.inputTokens ?: 0,
                    outputTokens = usage?.outputTokens ?: 0
                )
            )
        }
    }

    private fun recordToolRow(name: String, argsJson: String, result: String) {
        runCatching {
            chatStore.appendMessage(
                SharedChatStore.MessageRow(
                    id = chatStore.newMessageId(),
                    sessionId = sessionId,
                    role = "TOOL",
                    content = result.take(4000),
                    timestamp = System.currentTimeMillis(),
                    toolName = name,
                    toolArgs = argsJson.take(2000),
                    isError = result.startsWith("ERROR")
                )
            )
        }
    }

    private fun recordLlmCall(status: String, durationMillis: Int, errorMessage: String?) {
        runCatching {
            val usage = session.lastTurnUsage
            chatStore.insertLlmCall(
                SharedChatStore.LlmCallRow(
                    sessionId = sessionId,
                    providerId = provider.name,
                    model = provider.model,
                    kind = "chat",
                    inputTokens = usage?.inputTokens ?: 0,
                    outputTokens = usage?.outputTokens ?: 0,
                    durationMillis = durationMillis,
                    status = status,
                    errorMessage = errorMessage?.take(400),
                    stopReason = if (status == "success") "end_turn" else null,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun addSessionTokenTotals() {
        runCatching {
            val usage = session.lastTurnUsage ?: return
            chatStore.addSessionTokens(sessionId, usage.inputTokens, usage.outputTokens, usage.inputTokens)
        }
    }

    private fun upsertDbSession() {
        runCatching {
            chatStore.upsertSession(
                SharedChatStore.SessionRow(
                    id = sessionId,
                    title = runtime.sessions.titleFrom(firstPrompt ?: "session"),
                    createdAt = sessionCreatedAt,
                    updatedAt = System.currentTimeMillis(),
                    workspacePath = workspaceRoot,
                    providerId = provider.name,
                    model = provider.model
                )
            )
        }
    }

    /** Lifetime token usage line for `/costs` (the shared database is the source). */
    private fun costsOutput(): String {
        val totals = runCatching { chatStore.llmTotals() }.getOrNull()
            ?: return "Token usage unavailable (shared database could not be read)."
        val s = xp.summary()
        return buildString {
            appendLine("Lifetime (all front ends on this machine, from the shared DB):")
            appendLine("  llm calls ${totals.calls} (${totals.errors} errored) · tokens in ${totals.inputTokens} · out ${totals.outputTokens}")
            append("Level ${s.level} · ${s.totalXp} XP · ${s.streakDays}d streak")
        }
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
            xp.awardTurn(prompt.take(50).ifBlank { "chat turn" })
        }
    }
}
