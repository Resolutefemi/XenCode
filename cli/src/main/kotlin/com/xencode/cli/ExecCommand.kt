package com.xencode.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.xencode.core.state.XenEventLog
import kotlinx.coroutines.runBlocking

/**
 * `xencode exec "<prompt>"` — non-interactive one-shot agent turn (the
 * `-p`/`run` mode of the established coding agents): full tool loop, session
 * persisted, exit code reflects success. `--output-format json` prints a
 * machine-readable envelope.
 */
class ExecCommand : XeCommand(
    name = "exec",
    helpText = "Run one non-interactive agent turn with tools (print the final reply and exit)"
) {
    private val prompt by argument("prompt", help = "The instruction for the agent")
    private val outputFormat by option(
        "--output-format",
        help = "text (default) or json {reply, sessionId, toolCalls, ok}"
    )
    private val maxTurns by option("--max-turns", help = "Tool rounds per turn (default 16)").int()
    private val resumeId by option("--resume", metavar = "ID", help = "Continue a stored session by id")

    private val runtime by requireObject<CliRuntime>()

    override fun run() {
        val provider = CliAgents.activeProvider()
        val agent = CliAgents.runtime(runtime, provider)
        agent.ensureMcp()
        val session = agent.newSession(maxToolRounds = maxTurns ?: 16)

        var sessionId = agent.sessions.newId()
        var createdAt = System.currentTimeMillis()
        if (resumeId != null) {
            val stored = agent.sessions.load(resumeId!!)
                ?: throw com.github.ajalt.clikt.core.UsageError("No session with id `$resumeId`")
            session.restoreHistory(stored.messages)
            sessionId = stored.id
            createdAt = stored.createdAt
            agent.events.emit(XenEventLog.Types.SESSION_RESUMED, "$sessionId (exec)")
        }

        var toolCalls = 0
        val reply = runBlocking {
            session.send(
                prompt,
                onToolCall = { name, args ->
                    toolCalls++
                    if (outputFormat != "json") echo("[tool] $name $args", err = true)
                }
            )
        }

        agent.sessions.save(
            id = sessionId,
            title = agent.sessions.titleFrom(prompt),
            provider = provider.name,
            model = provider.model,
            createdAt = createdAt,
            messages = session.historySnapshot()
        )

        when (outputFormat) {
            "json" -> {
                val gson = com.google.gson.GsonBuilder().disableHtmlEscaping().create()
                echo(
                    gson.toJson(
                        linkedMapOf(
                            "ok" to (reply != null),
                            "reply" to reply,
                            "error" to session.lastFailure,
                            "sessionId" to sessionId,
                            "toolCalls" to toolCalls
                        )
                    )
                )
            }
            else -> {
                if (reply == null) {
                    echo("Request failed: ${session.lastFailure ?: "empty reply"}", err = true)
                } else {
                    echo(reply)
                }
            }
        }
        if (reply == null) throw ProgramResult(1)
    }
}
