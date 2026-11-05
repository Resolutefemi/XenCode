package com.xencode.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.xencode.core.agent.AgentRuntime
import com.xencode.core.agent.GoalRunner
import com.xencode.core.agent.SlashCommands
import com.xencode.core.goal.GoalDagFile
import com.xencode.core.state.XenEventLog
import com.xencode.core.telemetry.Telemetry
import com.xencode.core.tools.AgentSession
import com.xencode.core.tools.Permissions
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `xencode chat` — the interactive agent REPL against the active provider, or
 * one-shot with `--message`.
 *
 * The full Claude-Code-style surface: slash commands, MCP tools, permission
 * gate + decision cards, auto-checkpoints, TODO list, XP awards and session
 * autosave/resume (`.xencode/sessions`).
 *
 * Slash commands (see /help): /clear /compact /model /todo /checkpoint /session
 * /goal /dag /xp /mcp /events /decide /radar /system /yolo /exit.
 */
class ChatCommand : XeCommand(
    name = "chat",
    helpText = "Talk to the active provider (interactive REPL with slash commands, or one-shot with --message)"
) {
    private val message by option(
        "-m", "--message",
        help = "Send this single message, print the reply, and exit (no REPL)"
    )
    private val resume by option("--resume", metavar = "ID", help = "Resume a stored session by id (see `xencode session list`)")
    private val continueLast by option("--continue", "-c", help = "Resume the most recent session in this workspace").flag()
    private val yolo by option("--yolo", help = "Start in YOLO permission mode (allow every tool call)").flag()
    private val readOnly by option("--read-only", help = "Start in read-only permission mode (deny every mutation)").flag()

    private val runtime by requireObject<CliRuntime>()

    private lateinit var agent: AgentRuntime
    private lateinit var session: AgentSession
    private var sessionId: String = ""
    private var sessionCreatedAt: Long = 0
    private var firstPrompt: String? = null

    override fun run() {
        val provider = CliAgents.activeProvider()
        agent = CliAgents.runtime(runtime, provider)
        if (yolo) agent.setPermissionMode(Permissions.Mode.YOLO)
        if (readOnly) agent.setPermissionMode(Permissions.Mode.READ_ONLY)

        // MCP servers (status printed; failures are not fatal).
        agent.ensureMcp().forEach { (name, ok) ->
            echo(if (ok) "[mcp] $name connected" else "[mcp] $name FAILED: ${agent.mcp.serverErrors()[name] ?: ""}")
        }

        session = agent.newSession(maxToolRounds = 16)
        sessionId = agent.sessions.newId()
        sessionCreatedAt = System.currentTimeMillis()

        if (resume != null || continueLast) {
            val stored = when {
                resume != null -> agent.sessions.load(resume!!)
                    ?: throw UsageError("No session with id `$resume` in this workspace (see `xencode session list`)")
                else -> agent.sessions.latest()
                    ?: throw UsageError("No stored session in this workspace yet")
            }
            session.restoreHistory(stored.messages)
            sessionId = stored.id
            sessionCreatedAt = stored.createdAt
            firstPrompt = stored.title
            agent.events.emit(XenEventLog.Types.SESSION_RESUMED, "${stored.id} ${stored.title}")
            echo("Resumed session ${stored.id} (${stored.messages.size} messages, ${stored.provider}/${stored.model})")
        }

        echo("Provider ${provider.name} (${provider.kind}) · model ${provider.model} · workspace ${runtime.workspaceRoot}")
        echo("Permission mode: ${agent.permissionMode().name.lowercase()} · MCP tools: ${if (agent.mcp.connectedServers().isEmpty()) "none" else agent.mcp.tools().size}")

        val oneShotText = message
        if (oneShotText != null) {
            runBlocking { turn(oneShotText) || throw ProgramResult(1) }
            persistSession()
            return
        }

        echo("Type your message; /help lists commands; /exit or Ctrl-D leaves.")
        while (true) {
            echo("xencode> ", trailingNewline = false)
            System.out.flush()
            val line = readLine() ?: break // EOF (Ctrl-D / Ctrl-Z)
            val text = line.trim()
            if (text.isEmpty()) continue
            if (text.equals("exit", ignoreCase = true)) break
            val slash = SlashCommands.parse(text)
            if (slash != null) {
                if (!handleSlash(slash)) break
            } else {
                runBlocking { turn(text) }
            }
            echo()
        }
        persistSession()
        echo("Bye (session $sessionId saved).")
    }

    /** @return true to keep the REPL running, false to exit. */
    private fun handleSlash(cmd: SlashCommands.Parsed): Boolean {
        return when (cmd.name) {
            "/exit" -> false
            "/help" -> { echo(SlashCommands.help()); true }
            "/clear" -> {
                session.clear(); agent.events.emit(XenEventLog.Types.STATE_SYNC, "conversation cleared"); echo("Cleared.")
                true
            }
            "/compact" -> {
                val saved = session.compactHistory()
                agent.events.emit(XenEventLog.Types.COMPACTED, "saved $saved chars")
                echo(if (saved > 0) "Compacted: $saved characters of context dropped." else "Nothing to compact yet.")
                true
            }
            "/model" -> {
                val p = CliAgents.activeProvider()
                echo("Provider ${p.name} (${p.kind}) · model ${p.model} · ${p.baseUrl}")
                echo("Switch with `xencode config use --name <name>` (see `xencode config list`).")
                true
            }
            "/system" -> { echo(agent.systemPrompt()); true }
            "/yolo" -> {
                val next = if (agent.permissionMode() == Permissions.Mode.YOLO) Permissions.Mode.DEFAULT else Permissions.Mode.YOLO
                agent.setPermissionMode(next)
                echo("Permission mode: ${next.name.lowercase()}")
                true
            }
            "/todo" -> handleTodo(cmd.arg)
            "/checkpoint" -> handleCheckpoint(cmd.arg)
            "/session" -> {
                when {
                    cmd.arg.isBlank() -> echo("Session $sessionId (${session.historySnapshot().size} messages) · saved under .xencode/sessions/")
                    cmd.arg == "list" -> echo(renderSessionList())
                    cmd.arg == "save" -> { persistSession(); echo("Saved $sessionId.") }
                    else -> echo("Usage: /session [list|save]")
                }
                true
            }
            "/goal" -> handleGoal(cmd.arg)
            "/dag" -> { echo(renderGoalDag(runtime.workspaceRoot, GoalDagFile.load(runtime.workspaceRoot))); true }
            "/xp" -> { echo(renderXpSummary(MachineStores.ledger().summary())); true }
            "/mcp" -> {
                val servers = agent.mcp.connectedServers()
                if (servers.isEmpty()) {
                    echo("No MCP servers configured. Add one to ${runtime.workspaceRoot}/.xencode/mcp.json, then restart the chat.")
                } else {
                    echo("Connected: ${servers.joinToString(", ")}")
                    agent.mcp.tools().forEach { echo("  ${it.prefixedName} — ${it.description.take(70)}") }
                }
                agent.mcp.serverErrors().forEach { (name, err) -> echo("[error] $name: $err", err = true) }
                true
            }
            "/events" -> {
                agent.events.latest(cmd.arg.toIntOrNull() ?: 15).forEach { echo(XenEventLog.pretty(it)) }
                true
            }
            "/decide" -> handleDecide(cmd.arg)
            "/radar" -> {
                val t = Telemetry(File(runtime.workspaceRoot), agent.events).load()
                echo(Telemetry.renderRadar(t))
                echo("command streak: ${t.commandStreak} (best ${t.bestStreak}) · tool calls: ${t.toolCalls} · files written: ${t.filesWritten}")
                true
            }
            else -> { echo("Unknown command $cmd.name — /help lists them."); true }
        }
    }

    private fun handleTodo(arg: String): Boolean {
        val parts = arg.trim().split(Regex("\\s+"), limit = 2)
        when (parts.firstOrNull()?.lowercase() ?: "list") {
            "", "list" -> echo(agent.todo.render())
            "add" -> {
                val text = parts.getOrNull(1)?.trim().orEmpty()
                if (text.isEmpty()) echo("Usage: /todo add <text>")
                else echo(renderTodo(agent.todo.add(text)))
            }
            "done" -> {
                val needle = parts.getOrNull(1)?.trim().orEmpty()
                if (needle.isEmpty()) {
                    echo("Usage: /todo done <text fragment>")
                } else {
                    val next = agent.todo.complete(needle)
                    echo(if (next != null) renderTodo(next) else "No matching open item.")
                }
            }
            "clear" -> echo(renderTodo(agent.todo.clear()))
            else -> echo("Usage: /todo [list|add <text>|done <text>|clear]")
        }
        return true
    }

    private fun handleCheckpoint(arg: String): Boolean {
        val parts = arg.trim().split(Regex("\\s+"), limit = 2)
        when (parts.firstOrNull()?.lowercase() ?: "list") {
            "", "list" -> {
                val list = agent.checkpoints.list()
                if (list.isEmpty()) echo("No checkpoints yet (.xencode/checkpoints.json).")
                else list.forEach { echo("${it.id}  ${it.createdAt}  ${it.commit ?: "-"}  ${it.label}: ${it.summary.take(60)}") }
            }
            "create" -> {
                val cp = agent.checkpoints.create(parts.getOrNull(1)?.trim().orEmpty().ifBlank { "manual" }, "manual checkpoint from chat")
                echo(cp?.let { "Checkpoint ${it.id} at ${it.commit} (${it.filesChanged} files)." } ?: "No checkpoint: ${agent.checkpoints.lastError}")
            }
            "restore" -> {
                val id = parts.getOrNull(1)?.trim().orEmpty()
                val err = agent.checkpoints.restore(id)
                echo(if (err == null) "Restored $id." else err, err = err != null)
            }
            else -> echo("Usage: /checkpoint [list|create|restore <id>]")
        }
        return true
    }

    private fun handleGoal(arg: String): Boolean {
        val tokens = arg.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.first() == "list") {
            echo(renderGoalDag(runtime.workspaceRoot, GoalDagFile.load(runtime.workspaceRoot)))
            return true
        }
        if (tokens.first() == "run") {
            runGoalRunner(parseGoalArgs(tokens) as? GoalRequest.Run ?: GoalRequest.Run(2))
            return true
        }
        // Delegate add/done/... to the same engine + renderer as `xencode goal`.
        echo("Use `xencode goal <args>` for mutations; here only list and run are wired.")
        return true
    }

    private fun handleDecide(arg: String): Boolean {
        val open = agent.decisions.open()
        if (open.isEmpty()) {
            echo("No open decision cards.")
            return true
        }
        val parts = arg.trim().split(Regex("\\s+"))
        if (parts.firstOrNull() in setOf("approve", "reject")) {
            val choice = parts.first()
            val id = parts.getOrNull(1) ?: open.first().id
            val card = agent.decisions.resolve(id, choice)
            if (card != null) {
                agent.events.emit(XenEventLog.Types.DECISION_RESOLVED, "$id $choice", "card" to id, "choice" to choice)
                echo("Card $id resolved: $choice")
            } else echo("Could not resolve `$id` with `$choice` (already resolved or unknown).", err = true)
            return true
        }
        open.forEach { c ->
            echo("${c.id}  ${c.title}")
            echo("    ${c.body.take(140)}")
            echo("    options: ${c.options.joinToString(" | ")}")
        }
        echo("Resolve with /decide approve|reject <id>")
        return true
    }

    private fun renderSessionList(): String =
        agent.sessions.list().joinToString("\n") { m ->
            "${m.id}  ${m.updatedAt}  [${m.messageCount} msg] ${m.provider}/${m.model} — ${m.title.take(48)}"
        }.ifBlank { "(no stored sessions)" }

    private fun runGoalRunner(request: GoalRequest.Run) {
        echo("Running READY goals (parallel=${request.parallel}) — each node gets its own agent turn.")
        val summary = runBlocking {
            GoalRunner(
                workspaceRoot = File(runtime.workspaceRoot),
                provider = CliAgents.activeProvider(),
                client = com.xencode.core.provider.ChatClient(),
                runtime = agent
            ).run(parallel = request.parallel)
        }
        summary.results.forEach { r ->
            val where = r.worktreePath?.let { " in ${r.branch} ($it)" } ?: ""
            echo(if (r.ok) "[done] ${r.nodeId}$where — ${(r.reply ?: "").take(120)}" else "[fail] ${r.nodeId} — ${r.error}")
        }
        echo("Goals: ${summary.doneCount} done, ${summary.failedCount} failed.")
    }

    /** One user turn; @return true on success. */
    private suspend fun turn(text: String): Boolean {
        if (firstPrompt == null) firstPrompt = text
        var midLine = false
        val reply = session.send(
            text,
            onDelta = { delta ->
                if (delta.isNotEmpty()) {
                    echo(delta, trailingNewline = false)
                    System.out.flush()
                    midLine = true
                }
            },
            onToolCall = { name, args ->
                if (midLine) echo()
                midLine = false
                echo("[tool] $name $args")
            }
        )
        if (midLine) echo()
        if (reply == null) {
            val reason = session.lastFailure
                ?: "empty reply from the provider (endpoint answered without an SSE stream — check base URL and key)"
            echo("Request failed: $reason", err = true)
            return false
        }
        runCatching { agent.ledger.awardTurn(text.take(50).ifBlank { "chat turn" }) }
        agent.events.emit(XenEventLog.Types.TURN_COMPLETED, reply.take(80))
        persistSession()
        return true
    }

    private fun persistSession() {
        runCatching {
            agent.sessions.save(
                id = sessionId,
                title = agent.sessions.titleFrom(firstPrompt ?: "session"),
                provider = CliAgents.activeProvider().name,
                model = CliAgents.activeProvider().model,
                createdAt = sessionCreatedAt,
                messages = session.historySnapshot()
            )
        }
    }

    private fun renderXpSummary(s: com.xencode.core.telemetry.XpLedger.Summary): String =
        com.xencode.core.telemetry.XpMath.summaryLine(s.totalXp, s.streakDays) +
            " · today ${s.todayXp} XP · recent: " + s.recent.joinToString(", ") { "${it.kind}+${it.amount}" }

    private fun renderTodo(items: List<com.xencode.core.tools.TodoStore.TodoItem>): String =
        com.xencode.core.tools.TodoStore.render(items)
}
