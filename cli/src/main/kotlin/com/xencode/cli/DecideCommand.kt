package com.xencode.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.xencode.core.state.DecisionStore
import com.xencode.core.state.XenEventLog
import java.io.File

/**
 * `xencode decide` — resolve the open interactive decision cards (permission
 * asks, 3-strike pivots, `.mjs` `sync.requestUserDecision` calls).
 *
 * - `decide` / `list`           open cards with their options
 * - `decide approve <id>`       resolve positively
 * - `decide reject <id>`        resolve negatively
 * - `decide <id> <choice>`      any of the card's own options
 */
class DecideCommand : XeCommand(
    name = "decide",
    helpText = "Resolve open decision cards (permission asks and automation prompts)"
) {
    private val args by argument("args").multiple()
    private val runtime by requireObject<CliRuntime>()

    override fun run() {
        val store = DecisionStore(File(runtime.workspaceRoot))
        val events = XenEventLog(File(runtime.workspaceRoot))
        val verb = args.firstOrNull()?.lowercase() ?: "list"

        when (verb) {
            "list", "ls", "" -> {
                val open = store.open()
                if (open.isEmpty()) {
                    echo("No open decision cards.")
                    return
                }
                open.forEach { c ->
                    echo("${c.id}  ${c.title}")
                    echo("    ${c.body.take(160)}")
                    echo("    options: ${c.options.joinToString(" | ")}")
                }
                echo("Resolve with `xencode decide approve|reject <id>`.")
            }

            "approve", "reject" -> {
                val id = args.getOrNull(1) ?: store.open().firstOrNull()?.id
                    ?: run { echo("No open decision cards."); return }
                resolve(store, events, id, verb)
            }

            else -> {
                // `decide <id> <choice>` with one of the card's own options.
                val id = args.getOrNull(0) ?: throw UsageError("Usage: xencode decide [approve|reject] <id>")
                val choice = args.getOrNull(1) ?: throw UsageError("Usage: xencode decide <id> <choice>")
                resolve(store, events, id, choice)
            }
        }
    }

    private fun resolve(store: DecisionStore, events: XenEventLog, id: String, choice: String) {
        val card = store.resolve(id, choice)
        if (card == null) {
            echo("Could not resolve `$id` with `$choice` (unknown id, already resolved, or not one of its options).", err = true)
            throw ProgramResult(1)
        }
        events.emit(XenEventLog.Types.DECISION_RESOLVED, "$id $choice", "card" to id, "choice" to choice)
        echo("Card $id resolved: $choice.")
    }
}
