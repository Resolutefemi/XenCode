package com.xencode.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.option
import com.xencode.core.provider.ChatClient
import com.xencode.core.provider.PcConfigStore
import com.xencode.core.tools.AgentSession
import com.xencode.core.tools.LocalTools
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `xencode chat` — interactive REPL against the active provider, or one-shot
 * with `--message`.
 *
 * Text deltas are streamed to stdout as they arrive; tool calls are printed as
 * `[tool] <name> <args>` lines before local execution. The conversation is
 * multi-turn until `/exit`, `exit`, or EOF. A failed turn prints
 * [AgentSession.lastFailure] to stderr.
 */
class ChatCommand : XeCommand(
    name = "chat",
    helpText = "Talk to the active provider (interactive REPL, or one-shot with --message)"
) {
    private val message by option(
        "-m", "--message",
        help = "Send this single message, print the reply, and exit (no REPL)"
    )

    private val runtime by requireObject<CliRuntime>()

    override fun run() {
        val config = PcConfigStore.load()
        val provider = config.activeProvider()
            ?: throw UsageError(
                "No provider configured yet. Add one first:\n" +
                    "  xencode config add --name <name> --kind openai|anthropic --base-url <url> \\\n" +
                    "      --api-key <key> --model <model>\n" +
                    "Then run `xencode doctor` to verify it."
            )
        val workspace = runtime.workspaceRoot
        val tools = LocalTools(File(workspace))
        val systemPrompt = buildString {
            appendLine("You are XenCode, a command-line coding agent running on the user's machine.")
            appendLine("Workspace root: $workspace. Tool paths are workspace-relative and cannot escape it.")
            appendLine("Tools: read_file, write_file, list_files, run_command (30s timeout, runs inside the workspace).")
            appendLine("Rules:")
            appendLine("- Use the tools for anything that touches files or commands; never pretend to have run them.")
            appendLine("- Prefer small, verifiable steps; show what you are about to change before destructive edits.")
            appendLine("- Reply in English, concisely. No emoji.")
            appendLine("- If a request is unclear, ask one short clarifying question.")
        }
        val session = AgentSession(
            client = ChatClient(),
            config = provider,
            tools = tools,
            systemPrompt = systemPrompt
        )

        echo("Provider ${provider.name} (${provider.kind}) · model ${provider.model} · workspace $workspace")
        val oneShotText = message
        if (oneShotText != null) {
            runBlocking { oneShot(session, oneShotText) }
        } else {
            echo("Type your message; /exit or \"exit\" leaves. Tool calls print as [tool] lines.")
            repl(session)
        }
    }

    /** One --message turn; non-zero exit when the request fails. */
    private suspend fun oneShot(session: AgentSession, text: String) {
        val reply = turn(session, text)
        if (reply == null) {
            // [turn] already printed the failure reason to stderr.
            throw ProgramResult(1)
        }
    }

    /** Read-eval loop until /exit, exit, or EOF. */
    private fun repl(session: AgentSession) {
        while (true) {
            echo("xencode> ", trailingNewline = false)
            System.out.flush()
            val line = readLine() ?: break // EOF (Ctrl-D / Ctrl-Z)
            val text = line.trim()
            if (text.isEmpty()) continue
            if (text.equals("exit", ignoreCase = true) || text == "/exit") break
            runBlocking { turn(session, text) }
            echo()
        }
        echo("Bye.")
    }

    /**
     * One user turn: stream deltas live, print tool calls, and report failures
     * from [AgentSession.lastFailure] on stderr.
     */
    private suspend fun turn(session: AgentSession, text: String): String? {
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
            // lastFailure carries the transport error (HTTP status, timeout, ...);
            // a null with no failure means the stream ended without any content
            // (e.g. a gateway returning 200 + a non-SSE error envelope).
            val reason = session.lastFailure
                ?: "empty reply from the provider (endpoint answered without an SSE stream — check base URL and key)"
            echo("Request failed: $reason", err = true)
        }
        return reply
    }
}
