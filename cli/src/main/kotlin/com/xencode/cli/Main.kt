package com.xencode.cli

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.xencode.core.provider.PcConfigStore

/**
 * XenCode CLI — the PC-line entry point (Windows/macOS/Linux).
 *
 * `xencode [--workspace PATH] <command> ...`
 *
 * The workspace is resolved once in the root command (see Workspace.kt) and
 * handed to the subcommands through the context object; the root's [run] runs
 * before the invoked subcommand's, so every command can `by requireObject()`.
 */
class XenCode : CliktCommand(name = "xencode") {
    init {
        versionOption(CLI_VERSION, help = "Show version and exit") { "xencode-cli $it" }
        subcommands(
            ChatCommand(),
            ExecCommand(),
            GoalCommand(),
            DagCommand(),
            SessionCommand(),
            CheckpointCommand(),
            TodoCommand(),
            McpCommand(),
            RunCommand(),
            DecideCommand(),
            EventsCommand(),
            WorktreeCommand(),
            XpCommand(),
            ConfigCommand(),
            DoctorCommand(),
            InitCommand()
        )
    }

    private val workspace by option(
        "--workspace",
        metavar = "PATH",
        help = "Workspace root for this invocation (default: config defaultWorkspace, else the current directory)"
    )

    override fun help(context: Context): String =
        "XenCode command-line coding agent (chat / exec / goal / dag / session / checkpoint / todo / mcp / run / decide / events / worktree / xp / config / doctor / init)"

    override fun run() {
        val config = PcConfigStore.load()
        val currentDir = System.getProperty("user.dir") ?: "."
        currentContext.obj = loadRuntime(config, workspace, currentDir)
    }
}

/** Kept in sync with cli/build.gradle.kts `version`. */
const val CLI_VERSION = "1.1.0"

fun main(args: Array<String>) {
    // Ktor drags in slf4j-api but no provider, which would print a 3-line NOP
    // warning to stderr on every network-touching command; silence it before
    // any HttpClient is constructed (verbosity property exists since 2.0.10).
    System.setProperty("slf4j.internal.verbosity", "ERROR")
    // Stock-JVM GraalJS prints a fallback-implementation warning on every engine
    // start; silence it before any `xencode run` creates a polyglot Context.
    System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
    // A rejected chat turn or doctor failure exits non-zero via ProgramResult;
    // UsageError/PrintHelpMessage keep their standard Clikt semantics.
    XenCode().main(args)
}
