package com.xencode.cli

import com.xencode.core.provider.PcConfig
import java.io.File

/**
 * Workspace resolution, shared by every subcommand.
 *
 * Precedence (mirrors the Android app, which always has one workspace open):
 * 1. `--workspace PATH` on the command line;
 * 2. `defaultWorkspace` from `~/.xencode/pc-config.json`;
 * 3. the current directory.
 */
fun resolveWorkspace(arg: String?, defaultWorkspace: String?, currentDir: String): String {
    arg?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    defaultWorkspace?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return currentDir
}

/** Normalize any user-supplied path into an absolute, deduplicated form. */
fun canonicalizeWorkspace(path: String): String =
    File(path).absoluteFile.normalize().path.trimEnd(File.separatorChar).ifEmpty { File(path).absoluteFile.normalize().path }

/** Per-invocation state handed from the root command to its subcommands. */
class CliRuntime(
    /** Absolute workspace root the invocation operates on. */
    val workspaceRoot: String,
    /** True when the workspace came from `--workspace` or the config default; false for CWD fallback. */
    val workspaceExplicit: Boolean
)

fun loadRuntime(config: PcConfig, arg: String?, currentDir: String): CliRuntime {
    val raw = resolveWorkspace(arg, config.defaultWorkspace, currentDir)
    return CliRuntime(
        workspaceRoot = canonicalizeWorkspace(raw),
        workspaceExplicit = arg?.trim()?.isNotEmpty() == true || !config.defaultWorkspace.isNullOrBlank()
    )
}
