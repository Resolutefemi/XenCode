package com.xencode.cli

import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.xencode.core.state.XenEventLog
import java.io.File

/**
 * `xencode events` — tail the cross-platform sync log
 * (`<workspace>/.xencode/events.ndjson`): every tool call, checkpoint, node
 * transition, decision and verification the CLI/desktop/.mjs layer emits.
 */
class EventsCommand : XeCommand(
    name = "events",
    helpText = "Tail the .xencode/events.ndjson sync log (agent state transitions)"
) {
    private val args by argument("args").multiple()
    private val tail by option("--tail", "-n", help = "Number of lines to show (default 20)").int()
    private val raw by option("--raw", help = "Print the raw ndjson lines instead of the pretty form").flag()

    private val runtime by requireObject<CliRuntime>()

    override fun run() {
        val log = XenEventLog(File(runtime.workspaceRoot))
        val n = tail ?: args.firstOrNull()?.toIntOrNull() ?: 20
        val lines = log.latest(n.coerceIn(1, 500))
        if (lines.isEmpty()) {
            echo("No events yet (${runtime.workspaceRoot}/.xencode/events.ndjson).")
            return
        }
        if (raw || args.firstOrNull() == "raw") {
            lines.forEach { echo(it) }
        } else {
            lines.forEach { echo(XenEventLog.pretty(it)) }
        }
    }
}
