package com.xencode.core.tools

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Tool permission policy, persisted at `<workspace>/.xencode/permissions.json`.
 *
 * Three modes plus per-pattern overrides evaluated in the order deny > ask > allow:
 * - READ_ONLY: every mutating tool and every shell command is denied (unless allowlisted);
 * - DEFAULT:   file edits run, shell commands run unless they match the built-in
 *              dangerous list or a custom `ask` pattern (then a decision card is created);
 * - YOLO:      everything runs (still sandboxed to the workspace).
 *
 * Patterns are globs matched against `tool:<name>` and, for run_command, the
 * command line itself, e.g. `cmd:git push*`, `cmd:rm *`, `tool:write_file`.
 */
object Permissions {

    enum class Mode { READ_ONLY, DEFAULT, YOLO;

        companion object {
            fun parse(s: String?): Mode = when (s?.trim()?.lowercase()) {
                "read-only", "readonly", "ro" -> READ_ONLY
                "yolo", "full", "unsafe" -> YOLO
                else -> DEFAULT
            }
        }
    }

    sealed class Decision {
        object Allow : Decision()
        data class Deny(val reason: String) : Decision()
        data class Ask(val reason: String) : Decision()
    }

    /** Built-in commands that need explicit approval even in DEFAULT mode. */
    val DANGEROUS_COMMAND_PREFIXES = listOf(
        "rm ", "rm\t", "sudo ", "mkfs", "dd ", "shred ", "mv /", ":(){",
        "git push", "git reset --hard", "git clean -fd", "git checkout -- .", "git checkout -- .",
        "curl ", "wget ", "npm publish", "cargo publish", "docker system prune", "powershell ", "del /"
    )

    private val READ_ONLY_TOOLS = setOf("write_file", "edit_file", "delete_file", "run_command", "todo_write")

    fun check(mode: Mode, allow: List<String>, deny: List<String>, ask: List<String>, tool: String, argsJson: String): Decision {
        val argText = commandOf(argsJson) ?: ""
        fun matches(patterns: List<String>): String? {
            for (p in patterns) {
                val pat = p.trim()
                if (pat.isEmpty()) continue
                val hit = when {
                    pat.startsWith("tool:") -> LocalTools.globToRegex(pat.removePrefix("tool:")).matches(tool)
                    // Command patterns match whole command lines: "*" may cross
                    // slashes there (unlike file globs) — commands contain paths.
                    pat.startsWith("cmd:") -> argText.isNotEmpty() &&
                        commandGlobToRegex(pat.removePrefix("cmd:")).matches(argText)
                    else -> argText.isNotEmpty() && commandGlobToRegex(pat).matches(argText)
                }
                if (hit) return pat
            }
            return null
        }

        matches(deny)?.let { return Decision.Deny("matched deny rule `$it`") }
        matches(ask)?.let { return Decision.Ask("matched ask rule `$it`") }
        matches(allow)?.let { return Decision.Allow }

        return when (mode) {
            Mode.YOLO -> Decision.Allow
            Mode.READ_ONLY ->
                if (tool in READ_ONLY_TOOLS) Decision.Deny("read-only mode: `$tool` is disabled")
                else Decision.Allow
            Mode.DEFAULT -> when {
                tool == "run_command" && DANGEROUS_COMMAND_PREFIXES.any { argText.startsWith(it.trim()) } ->
                    Decision.Ask("command `${argText.take(80)}` looks destructive")
                else -> Decision.Allow
            }
        }
    }

    private fun commandOf(argsJson: String): String? = runCatching {
        val obj = JsonParser.parseString(argsJson.ifBlank { "{}" }).asJsonObject
        obj.get("command")?.takeIf { !it.isJsonNull }?.asString
    }.getOrNull()

    /** Command-line glob: "*" matches anything (including '/'), "?" one char. */
    fun commandGlobToRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        for (c in pattern) when (c) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            else -> sb.append(Regex.escape(c.toString()))
        }
        return Regex(sb.append('$').toString())
    }
}

/** File-backed permissions configuration (`.xencode/permissions.json`). */
class PermissionStore(private val workspaceRoot: File) {

    data class Config(
        val mode: String = "default",
        val allow: List<String> = emptyList(),
        val deny: List<String> = emptyList(),
        val ask: List<String> = emptyList()
    )

    private val file: File get() = File(File(workspaceRoot, ".xencode"), "permissions.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun load(): Config = runCatching {
        gson.fromJson(file.readText(Charsets.UTF_8), Config::class.java) ?: Config()
    }.getOrElse { Config() }

    fun save(config: Config) {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(config), Charsets.UTF_8)
    }

    fun mode(): Permissions.Mode = Permissions.Mode.parse(load().mode)

    fun check(tool: String, argsJson: String): Permissions.Decision {
        val c = load()
        return Permissions.check(Permissions.Mode.parse(c.mode), c.allow, c.deny, c.ask, tool, argsJson)
    }
}

/** Small helper for the (tool, args) -> decision flow used by CLI, desktop and .mjs bindings. */
object PermissionGates {
    /** Extracts a short human label for a denied/asked tool call, for decision cards. */
    fun describe(tool: String, argsJson: String): String {
        val obj: JsonObject = runCatching {
            JsonParser.parseString(argsJson.ifBlank { "{}" }).asJsonObject
        }.getOrNull() ?: JsonObject()
        val path = obj.get("path")?.takeIf { !it.isJsonNull }?.asString
        val cmd = obj.get("command")?.takeIf { !it.isJsonNull }?.asString
        return when {
            tool == "run_command" && cmd != null -> "run_command: ${cmd.take(120)}"
            path != null -> "$tool: $path"
            else -> tool
        }
    }
}
