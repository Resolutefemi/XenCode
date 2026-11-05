package com.xencode.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import java.text.SimpleDateFormat
import java.util.Date

/**
 * `xencode session` — manage the stored chat sessions under
 * `<workspace>/.xencode/sessions/` (the PC counterpart of the Android Room
 * chat_session tables).
 *
 * - `session list`          stored sessions, newest first
 * - `session show <id>`     full transcript (roles + tool calls)
 * - `session rm <id>`       delete one
 * - resume with `xencode chat --resume <id>` / `xencode chat --continue`
 */
class SessionCommand : XeCommand(
    name = "session",
    helpText = "Manage stored chat sessions (list/show/rm; resume via `xencode chat --resume <id>`)"
) {
    private val args by argument("args").multiple()
    private val runtime by requireObject<CliRuntime>()

    override fun run() {
        val agent = com.xencode.core.state.SessionStore(java.io.File(runtime.workspaceRoot))
        val verb = args.firstOrNull()?.lowercase() ?: "list"
        when (verb) {
            "list", "ls", "" -> {
                val list = agent.list()
                if (list.isEmpty()) {
                    echo("No stored sessions in ${runtime.workspaceRoot}/.xencode/sessions/ yet.")
                    return
                }
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm")
                list.forEach { m ->
                    echo("${m.id}  ${fmt.format(Date(m.updatedAt))}  [${m.messageCount} msg] ${m.provider}/${m.model} — ${m.title.take(56)}")
                }
                echo("Resume with `xencode chat --resume <id>`.")
            }

            "show" -> {
                val id = args.getOrNull(1) ?: throw UsageError("Usage: xencode session show <id>")
                val s = agent.load(id) ?: throw UsageError("No session with id `$id`")
                echo("Session ${s.id} — ${s.title}")
                echo("Provider ${s.provider} · model ${s.model} · ${s.messages.size} messages")
                echo()
                s.messages.forEach { m ->
                    when {
                        m.toolCallId != null -> echo("  tool> ${(m.content ?: "").take(200)}")
                        m.toolCalls != null -> echo("agent> (calls: ${m.toolCalls.orEmpty().joinToString { it.name }})")
                        else -> echo("${if (m.role == "user") "user" else "agent"}> ${m.content ?: ""}")
                    }
                }
            }

            "rm" -> {
                val id = args.getOrNull(1) ?: throw UsageError("Usage: xencode session rm <id>")
                if (!agent.delete(id)) throw ProgramResult(1)
                echo("Deleted $id.")
            }

            else -> throw UsageError("Unknown subcommand `$verb` (list | show | rm)")
        }
    }
}
