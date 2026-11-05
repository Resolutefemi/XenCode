package com.xencode.cli

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.xencode.core.tools.TodoStore
import java.io.File

/**
 * `xencode todo` — the shared TODO list (`<workspace>/.xencode/TODO.json`),
 * the same list the agent reads/writes through its todo_write / todo_read
 * tools and the desktop TODO panel renders.
 *
 * - `todo` / `list`        render the checklist
 * - `todo add <text> [--priority high|medium|low]`
 * - `todo done <text fragment>`
 * - `todo clear`
 */
class TodoCommand : XeCommand(
    name = "todo",
    helpText = "The shared TODO list (agent todo_write/todo_read and the UIs all see this file)"
) {
    private val args by argument("args").multiple()
    private val priority by option("--priority", help = "Priority for `add`: high | medium | low")
    private val runtime by requireObject<CliRuntime>()

    override fun run() {
        val todo = TodoStore(File(runtime.workspaceRoot))
        when (val verb = args.firstOrNull()?.lowercase() ?: "list") {
            "list", "ls", "" -> echo(todo.render())

            "add" -> {
                val text = args.drop(1).joinToString(" ").trim()
                if (text.isEmpty()) throw UsageError("Usage: xencode todo add <text> [--priority high]")
                val p = priority?.lowercase()?.takeIf { it in setOf("high", "medium", "low") } ?: "medium"
                echo(TodoStore.render(todo.add(text, p)))
            }

            "done" -> {
                val needle = args.drop(1).joinToString(" ").trim()
                if (needle.isEmpty()) throw UsageError("Usage: xencode todo done <text fragment>")
                val next = todo.complete(needle)
                if (next == null) echo("No open item matching `$needle`.")
                else echo(TodoStore.render(next))
            }

            "clear" -> echo(TodoStore.render(todo.clear()))

            else -> throw UsageError("Unknown subcommand `$verb` (list | add | done | clear)")
        }
    }
}
