package com.xencode.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.xencode.core.mjs.MjsEngine
import com.xencode.core.state.CheckpointStore
import com.xencode.core.state.DecisionStore
import com.xencode.core.state.XenEventLog
import com.xencode.core.telemetry.Telemetry
import java.io.File

/**
 * `xencode run <script.mjs>` — execute a `.mjs` automation workflow on the
 * embedded JS engine with the `xencode:core` bindings (fs / ast / pty / git /
 * sync). `--example` prints the canonical script.
 */
class RunCommand : XeCommand(
    name = "run",
    helpText = "Execute a .mjs automation script on the embedded engine (xencode:core bindings)"
) {
    private val script by argument("script", help = "Path to the .mjs script (workspace-relative or absolute)").optional()
    private val example by option("--example", help = "Print an example .mjs script and exit").flag()

    private val runtime by requireObject<CliRuntime>()

    override fun run() {
        if (example) {
            echo(MjsEngine.EXAMPLE_SCRIPT.trim())
            return
        }
        val path = script ?: throw com.github.ajalt.clikt.core.UsageError("Usage: xencode run <script.mjs> (or --example)")
        val file = File(path).let { if (it.isAbsolute) it else File(runtime.workspaceRoot, path) }

        val root = File(runtime.workspaceRoot)
        val events = XenEventLog(root)
        val engine = MjsEngine(
            workspaceRoot = root,
            events = events,
            decisions = DecisionStore(root),
            checkpoints = CheckpointStore(root),
            telemetry = Telemetry(root, events)
        )
        val result = engine.run(file)

        if (result.ok) {
            result.value?.let { echo(it) }
        } else {
            echo(result.error ?: "script failed", err = true)
        }
        echo(
            "(${result.durationMs} ms · files: ${result.filesWritten.size} · commands: ${result.commandsRun.size}" +
                (if (result.decisionsCreated > 0) " · decision cards: ${result.decisionsCreated}" else "") + ")"
        )
        if (!result.ok) throw ProgramResult(1)
    }
}
