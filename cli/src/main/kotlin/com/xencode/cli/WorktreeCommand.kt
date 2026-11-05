package com.xencode.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.xencode.core.agent.Worktrees
import java.io.File

/**
 * `xencode worktree` — the isolated-git-worktree swimlanes the goal runner
 * creates (branches `xencode/<id>`). Worktrees live outside the repo tree so
 * they never pollute the main status or checkpoints.
 *
 * - `worktree list` / `create <id>` / `remove <id>`
 */
class WorktreeCommand : XeCommand(
    name = "worktree",
    helpText = "Isolated git worktrees (xencode/<id> branches) for parallel goal swimlanes"
) {
    private val args by argument("args").multiple()
    private val runtime by requireObject<CliRuntime>()

    override fun run() {
        val root = File(runtime.workspaceRoot)
        if (!Worktrees.isGitRepo(root)) {
            echo("This workspace is not a git repository — worktrees need git.", err = true)
            throw ProgramResult(1)
        }
        when (val verb = args.firstOrNull()?.lowercase() ?: "list") {
            "list", "ls", "" -> {
                val list = Worktrees.list(root)
                if (list.isEmpty()) {
                    echo("No xencode worktrees (they appear when `xencode goal run --parallel 2+` runs in a git repo).")
                    return
                }
                list.forEach { echo("${it.id.padEnd(12)} ${it.branch.padEnd(20)} ${it.path}") }
            }

            "create" -> {
                val id = args.getOrNull(1) ?: throw UsageError("Usage: xencode worktree create <id>")
                val wt = Worktrees.create(root, id)
                if (wt == null) {
                    echo(Worktrees.lastError ?: "worktree create failed", err = true)
                    throw ProgramResult(1)
                }
                echo("Created ${wt.branch} at ${wt.path}")
            }

            "remove", "rm" -> {
                val id = args.getOrNull(1) ?: throw UsageError("Usage: xencode worktree remove <id>")
                if (!Worktrees.remove(root, id)) {
                    echo(Worktrees.lastError ?: "worktree remove failed", err = true)
                    throw ProgramResult(1)
                }
                echo("Removed worktree $id.")
            }

            else -> throw UsageError("Unknown subcommand `$verb` (list | create | remove)")
        }
    }
}
