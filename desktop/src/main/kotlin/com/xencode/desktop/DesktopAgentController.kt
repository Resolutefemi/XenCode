package com.xencode.desktop

import com.xencode.core.provider.ChatClient
import com.xencode.core.provider.ProviderConfig
import com.xencode.core.telemetry.XpLedger
import com.xencode.core.telemetry.XpMath
import com.xencode.core.tools.AgentSession
import com.xencode.core.tools.LocalTools
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

/** One rendered row of the conversation transcript. */
sealed class ChatEntry {
    data class User(val text: String) : ChatEntry()
    data class Assistant(val text: String) : ChatEntry()
    data class ToolCall(val name: String, val argsJson: String) : ChatEntry()
    data class ErrorLine(val message: String) : ChatEntry()
}

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

/**
 * "View model" for the Chat pane. A plain Kotlin class (no Android lifecycle
 * imports): it wraps [AgentSession] — the shared chat + local-tool loop from
 * :core — for one provider + workspace, and exposes the UI state as StateFlows.
 *
 * Streaming runs on Dispatchers.IO; StateFlow updates are thread-safe so Compose
 * can collect them on the UI thread. One controller per (provider, workspace)
 * pair is created by [DesktopAppState.buildController] and disposed when either
 * changes.
 */
class DesktopAgentController(
    val provider: ProviderConfig,
    val workspaceRoot: String,
    private val ledger: XpLedger = DesktopXp.defaultLedger(),
    client: ChatClient = ChatClient(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val ioScope: CoroutineScope = scope

    private val _messages = MutableStateFlow<List<ChatEntry>>(emptyList())
    val messages: StateFlow<List<ChatEntry>> = _messages.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val session = AgentSession(
        client = client,
        config = provider,
        tools = LocalTools(File(workspaceRoot)),
        systemPrompt = systemPromptFor(workspaceRoot)
    )

    private var sendJob: Job? = null

    /**
     * Sends one user turn. The assistant text streams into [streamingText] delta by
     * delta; tool calls requested by the model are appended to [messages] as they
     * execute (the tool loop itself keeps running inside [AgentSession]). When the
     * turn ends on a successful non-tool round, the turn award (+5 XP) is written
     * to the per-machine ledger.
     */
    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _busy.value) return
        _error.value = null
        _messages.value = _messages.value + ChatEntry.User(prompt)
        _busy.value = true
        _streamingText.value = ""
        sendJob = ioScope.launch {
            val streamed = StringBuilder()
            val outcome = session.send(
                userText = prompt,
                onDelta = { delta ->
                    if (delta.isNotEmpty()) {
                        streamed.append(delta)
                        _streamingText.value = streamed.toString()
                    }
                },
                onToolCall = { name, argsJson ->
                    _messages.value = _messages.value + ChatEntry.ToolCall(name, argsJson)
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
        }
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
        ioScope.cancel()
    }

    private fun awardTurnXp(turnSuccessful: Boolean, prompt: String) {
        if (DesktopXp.turnAwardFor(turnSuccessful) <= 0L) return
        runCatching {
            ledger.awardTurn(prompt.take(50).ifBlank { "chat turn" })
        }
    }

    companion object {
        fun systemPromptFor(workspaceRoot: String): String = """
            You are XenCode, an AI coding agent running as a desktop application.

            - Current workspace: $workspaceRoot
            - Local tools available: read_file, write_file, list_files, run_command. They are rooted at the workspace; paths outside it are rejected.
            - Prefer inspecting the workspace with the tools over guessing. Be concise and practical.
            - When you change files, briefly say what you changed and why.
            - If a request is ambiguous or destructive, ask one short clarifying question first.
        """.trimIndent()
    }
}
