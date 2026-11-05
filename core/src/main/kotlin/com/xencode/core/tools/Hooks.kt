package com.xencode.core.tools

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Hook runner, persisted at `<workspace>/.xencode/hooks.json`.
 *
 * Pre-tool-use hooks run before a tool executes (env: XENCODE_HOOK_TOOL,
 * XENCODE_HOOK_ARGS); a non-zero exit blocks the call and the hook's stdout
 * becomes the block reason. Post-tool-use hooks run afterwards (also gets
 * XENCODE_HOOK_RESULT) and their output is appended to the tool result.
 *
 * `matcher` is a glob against the tool name ("*" or "**" matches everything).
 */
object Hooks {

    data class Hook(val matcher: String = "*", val command: String)

    data class Config(
        val preToolUse: List<Hook> = emptyList(),
        val postToolUse: List<Hook> = emptyList()
    )

    fun matches(matcher: String, tool: String): Boolean =
        LocalTools.globToRegex(matcher.ifBlank { "*" }).matches(tool)
}

class HookRunner(
    private val workspaceRoot: File,
    private val config: Hooks.Config = Hooks.Config()
) {

    /** Runs matching pre-hooks; null = proceed, non-null = block with this reason. */
    fun runPre(tool: String, argsJson: String): String? {
        val hooks = config.preToolUse.filter { Hooks.matches(it.matcher, tool) }
        if (hooks.isEmpty()) return null
        for (h in hooks) {
            val out = exec(h.command, tool, argsJson, result = null) ?: return null
            if (!out.first) return out.second.ifBlank { "blocked by pre-tool hook" }
        }
        return null
    }

    /** Runs matching post-hooks; returns their stdout to append to the result. */
    fun runPost(tool: String, argsJson: String, result: String): String? {
        val hooks = config.postToolUse.filter { Hooks.matches(it.matcher, tool) }
        if (hooks.isEmpty()) return null
        val sb = StringBuilder()
        for (h in hooks) {
            val out = exec(h.command, tool, argsJson, result) ?: continue
            if (out.second.isNotBlank()) sb.appendLine(out.second.trim())
        }
        return sb.toString().ifBlank { null }
    }

    /** @return (exitCode==0, stdout) or null when the command failed to start. */
    private fun exec(command: String, tool: String, argsJson: String, result: String?): Pair<Boolean, String>? {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val parts = if (isWindows) listOf("cmd", "/c", command) else listOf("sh", "-c", command)
        return runCatching {
            val proc = ProcessBuilder(parts)
                .directory(workspaceRoot)
                .apply {
                    environment()["XENCODE_HOOK_TOOL"] = tool
                    environment()["XENCODE_HOOK_ARGS"] = argsJson.take(4000)
                    if (result != null) environment()["XENCODE_HOOK_RESULT"] = result.take(4000)
                }
                .redirectErrorStream(true)
                .start()
            val buf = java.io.ByteArrayOutputStream()
            proc.inputStream.use { input ->
                val chunk = ByteArray(4 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (buf.size() > 4000) break
                    buf.write(chunk, 0, n)
                }
            }
            proc.waitFor(10, TimeUnit.SECONDS)
            (proc.exitValue() == 0) to buf.toString(Charsets.UTF_8).trim()
        }.getOrNull()
    }
}

/** File-backed hook configuration (`.xencode/hooks.json`). */
class HookStore(private val workspaceRoot: File) {

    private val file: File get() = File(File(workspaceRoot, ".xencode"), "hooks.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun load(): Hooks.Config = runCatching {
        gson.fromJson(file.readText(Charsets.UTF_8), Hooks.Config::class.java) ?: Hooks.Config()
    }.getOrElse { Hooks.Config() }

    fun save(config: Hooks.Config) {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(config), Charsets.UTF_8)
    }

    fun runner(): HookRunner = HookRunner(workspaceRoot, load())

    companion object {
        val EXAMPLE_JSON: String = """
            {
              "preToolUse": [
                { "matcher": "run_command", "command": "echo blocked-before-destructive-commands" }
              ],
              "postToolUse": [
                { "matcher": "write_file", "command": "git status --short" }
              ]
            }
        """.trimIndent()
    }
}
