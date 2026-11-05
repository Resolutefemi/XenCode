package com.xencode.cli

import com.github.ajalt.clikt.core.requireObject
import com.xencode.core.goal.GoalDagFile

/**
 * `xencode dag` — read-only render of the workspace goal DAG, byte-identical to
 * `xencode goal list` (and to the Android `/dag` output). Use `goal` to mutate.
 */
class DagCommand : XeCommand(
    name = "dag",
    helpText = "Render the workspace goal DAG (read-only; use `goal` to mutate)"
) {
    private val runtime by requireObject<CliRuntime>()

    override fun run() {
        val root = runtime.workspaceRoot
        echo(renderGoalDag(root, GoalDagFile.load(root)))
    }
}
