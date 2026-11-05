package com.xencode.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.xencode.core.state.CheckpointStore
import com.xencode.core.state.XenEventLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * `xencode checkpoint` — the Time-Machine ledger: atomic git commits taken
 * before risky agent work (and restorable by id). Non-git workspaces report a
 * clear no-op.
 *
 * - `checkpoint` / `list`             list the ledger
 * - `checkpoint create <label> <summary...>`   snapshot the current tree
 * - `checkpoint restore <id>`         `git reset --hard` to that commit
 * - `checkpoint diff <id>`            diff between the commit and now
 */
class CheckpointCommand : XeCommand(
    name = "checkpoint",
    helpText = "Time-machine checkpoints: atomic git snapshots the agent takes before risky edits"
) {
    private val args by argument("args").multiple()
    private val runtime by requireObject<CliRuntime>()

    override fun run() {
        val store = CheckpointStore(File(runtime.workspaceRoot))
        val verb = args.firstOrNull()?.lowercase() ?: "list"
        when (verb) {
            "list", "ls", "" -> {
                val list = store.list()
                if (list.isEmpty()) {
                    echo("No checkpoints yet (${runtime.workspaceRoot}/.xencode/checkpoints.json).")
                    if (!store.isGitRepo()) echo("Note: this workspace is not a git repository — checkpoints need git.", err = true)
                    return
                }
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm")
                list.reversed().forEach { cp ->
                    echo("${cp.id}  ${fmt.format(Date(cp.createdAt))}  ${cp.commit ?: "-".padEnd(7)}  ${cp.label}: ${cp.summary.take(60)} (${cp.filesChanged} files)")
                }
            }

            "create", "add" -> {
                val label = args.getOrNull(1)?.trim().orEmpty().ifBlank { "manual" }
                val summary = args.drop(2).joinToString(" ").ifBlank { "manual checkpoint" }
                val cp = store.create(label, summary)
                if (cp == null) {
                    echo("No checkpoint created: ${store.lastError}", err = true)
                    throw ProgramResult(1)
                }
                echo("Checkpoint ${cp.id} at ${cp.commit} — ${cp.filesChanged} files.")
            }

            "restore" -> {
                val id = args.getOrNull(1) ?: throw UsageError("Usage: xencode checkpoint restore <id>")
                val err = store.restore(id)
                if (err != null) {
                    echo(err, err = true)
                    throw ProgramResult(1)
                }
                echo("Restored workspace to checkpoint $id (git reset --hard).")
            }

            "diff" -> {
                val id = args.getOrNull(1) ?: throw UsageError("Usage: xencode checkpoint diff <id>")
                val out = store.diff(id)
                echo(out ?: "diff failed", err = out == null)
                if (out == null) throw ProgramResult(1)
            }

            else -> throw UsageError("Unknown subcommand `$verb` (list | create | restore | diff)")
        }
    }
}
