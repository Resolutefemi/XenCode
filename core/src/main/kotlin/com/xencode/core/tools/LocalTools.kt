package com.xencode.core.tools

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The tool contract consumed by [AgentSession]. Implementations supply their own
 * schemas (what the model sees) and execute calls; [LocalTools] is the built-in
 * set, MCP tools join through the same surface (see AgentRuntime).
 */
interface ToolExecutor {
    fun schemas(): List<ToolSpec>
    fun execute(name: String, argsJson: String): String
}

/** Wire-format tool description handed to the chat transport. */
data class ToolSpec(val name: String, val description: String, val parametersJson: String)

/**
 * Local execution tools for the PC line: file IO, surgical edits, glob/grep search,
 * a sandboxed shell and the shared TODO list — all rooted at the active workspace.
 * Every tool returns a string (results and errors alike) so the model can read
 * them; errors are prefixed with "ERROR:".
 *
 * Tool surface parity targets the established coding-agent set
 * (read/write/edit/glob/grep/bash/todo).
 */
class LocalTools(private val workspaceRoot: File) : ToolExecutor {

    companion object {
        /** Reject anything that escapes the workspace (including absolute paths). */
        fun resolve(root: File, rel: String): File? {
            val trimmed = rel.trim()
            if (trimmed.startsWith("/") || trimmed.startsWith("\\\\") ||
                trimmed.startsWith("~") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(trimmed)
            ) return null
            val cleaned = trimmed.trimStart('/', '\\')
            if (cleaned.isEmpty()) return root
            val f = File(root, cleaned).normalize()
            return if (f == root || f.startsWith(root)) f else null
        }

        /** Glob pattern -> regex ("**" crosses directories, "*" does not). */
        fun globToRegex(pattern: String): Regex {
            val sb = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                        sb.append(".*")
                        i++
                        if (i + 1 < pattern.length && pattern[i + 1] == '/') i++ // "**/" also eats the slash
                    }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex("^${sb}$")
        }

        /** Directories never worth searching or listing. */
        private val SKIP_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".idea", "__pycache__", ".xencode")

        private const val MAX_OUTPUT = 12_000

        val TOOL_SCHEMAS: List<ToolSpec> = listOf(
            ToolSpec(
                "read_file",
                "Read a UTF-8 text file from the workspace. Returns up to 12000 characters; use offset/limit for large files.",
                """{"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative path"},"offset":{"type":"integer","description":"1-based line to start from"},"limit":{"type":"integer","description":"Maximum number of lines to return"}},"required":["path"]}"""
            ),
            ToolSpec(
                "write_file",
                "Create or overwrite a UTF-8 text file in the workspace. Parent directories are created.",
                """{"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative path"},"content":{"type":"string","description":"Full file content"}},"required":["path","content"]}"""
            ),
            ToolSpec(
                "edit_file",
                "Replace an exact string in a workspace file. Fails if old_str is missing or (without replace_all) not unique; pass an empty old_str on a missing file to create it.",
                """{"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative path"},"old_str":{"type":"string","description":"Exact text to replace"},"new_str":{"type":"string","description":"Replacement text"},"replace_all":{"type":"boolean","description":"Replace every occurrence instead of requiring uniqueness"}},"required":["path","old_str","new_str"]}"""
            ),
            ToolSpec(
                "list_files",
                "List entries of a workspace directory (max 400 entries).",
                """{"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative directory (default .)"},"recursive":{"type":"boolean","description":"Also list subdirectories up to depth 3"}},"required":[]}"""
            ),
            ToolSpec(
                "glob",
                "Find files by glob pattern (e.g. \"src/**/*.kt\", \"*.md\"). \"**\" crosses directories, \"*\" does not. Returns up to 200 matches.",
                """{"type":"object","properties":{"pattern":{"type":"string","description":"Glob pattern"},"path":{"type":"string","description":"Workspace-relative directory to search (default .)"}},"required":["pattern"]}"""
            ),
            ToolSpec(
                "grep",
                "Search file contents with a regular expression. Output modes: content (default, with line numbers), files_with_matches, count.",
                """{"type":"object","properties":{"pattern":{"type":"string","description":"Regular expression"},"path":{"type":"string","description":"Workspace-relative file or directory (default .)"},"glob":{"type":"string","description":"Only files matching this glob"},"output_mode":{"type":"string","description":"content | files_with_matches | count"},"ignore_case":{"type":"boolean","description":"Case-insensitive match"},"head_limit":{"type":"integer","description":"Max result lines (default 100)"}},"required":["pattern"]}"""
            ),
            ToolSpec(
                "delete_file",
                "Delete a file (or empty directory) inside the workspace.",
                """{"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative path"}},"required":["path"]}"""
            ),
            ToolSpec(
                "run_command",
                "Run a shell command in the workspace (default 30s timeout, up to 600s via timeout_sec). Prefer this for builds, git and searches.",
                """{"type":"object","properties":{"command":{"type":"string","description":"The command line to run"},"timeout_sec":{"type":"integer","description":"Timeout in seconds (1..600, default 30)"}},"required":["command"]}"""
            ),
            ToolSpec(
                "todo_write",
                "Replace the shared TODO list (.xencode/TODO.json; same list the user sees via `xencode todo`). Pass the full list every time.",
                """{"type":"object","properties":{"todos":{"type":"array","items":{"type":"object","properties":{"content":{"type":"string"},"status":{"type":"string","description":"pending | active | completed"},"priority":{"type":"string","description":"high | medium | low"}},"required":["content"]}}},"required":["todos"]}"""
            ),
            ToolSpec(
                "todo_read",
                "Read the shared TODO list as a rendered checklist.",
                """{"type":"object","properties":{},"required":[]}"""
            )
        )
    }

    private val root: File = workspaceRoot.normalize()
    private val todo: TodoStore = TodoStore(root)
    private val gson = Gson()

    fun rootDir(): File = root

    override fun schemas(): List<ToolSpec> = TOOL_SCHEMAS

    fun execute(name: String, argsJson: String, todoStoreOverride: TodoStore? = null): String {
        val args = runCatching {
            JsonParser.parseString(argsJson.ifBlank { "{}" }).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull() ?: return "ERROR: arguments are not a JSON object"
        val str = { key: String -> args.get(key)?.takeIf { !it.isJsonNull }?.asString }
        val int = { key: String -> args.get(key)?.takeIf { !it.isJsonNull }?.asInt }
        val bool = { key: String -> args.get(key)?.takeIf { !it.isJsonNull }?.asBoolean }
        return try {
            when (name) {
                "read_file" -> {
                    val f = resolve(root, str("path") ?: return "ERROR: missing path") ?: return "ERROR: path escapes the workspace"
                    if (!f.isFile) return "ERROR: not a file: ${str("path")}"
                    val lines = f.readText(Charsets.UTF_8).split('\n')
                    val offset = (int("offset") ?: 1).coerceAtLeast(1)
                    val limit = (int("limit") ?: lines.size).coerceAtLeast(0)
                    val slice = lines.drop(offset - 1).take(limit)
                    clip(slice.joinToString("\n"), MAX_OUTPUT)
                }
                "write_file" -> {
                    val f = resolve(root, str("path") ?: return "ERROR: missing path") ?: return "ERROR: path escapes the workspace"
                    f.parentFile?.mkdirs()
                    f.writeText(str("content") ?: "", Charsets.UTF_8)
                    "Wrote ${str("content")?.length ?: 0} chars to ${str("path")}"
                }
                "edit_file" -> {
                    val f = resolve(root, str("path") ?: return "ERROR: missing path") ?: return "ERROR: path escapes the workspace"
                    val oldStr = str("old_str") ?: ""
                    val newStr = str("new_str") ?: ""
                    if (!f.isFile) {
                        if (oldStr.isEmpty()) {
                            f.parentFile?.mkdirs()
                            f.writeText(newStr, Charsets.UTF_8)
                            "Created ${str("path")} (${newStr.length} chars)"
                        } else {
                            "ERROR: not a file: ${str("path")} (pass an empty old_str to create it)"
                        }
                    } else {
                        val content = f.readText(Charsets.UTF_8)
                        if (oldStr.isEmpty()) return "ERROR: old_str is empty but the file exists; provide the exact text to replace"
                        val count = countOccurrences(content, oldStr)
                        when {
                            count == 0 -> "ERROR: old_str not found in ${str("path")}"
                            count > 1 && !(bool("replace_all") ?: false) ->
                                "ERROR: old_str occurs $count times in ${str("path")}; narrow it down or pass replace_all=true"
                            else -> {
                                val next = if (bool("replace_all") == true) content.replace(oldStr, newStr)
                                else content.replaceFirst(oldStr, newStr)
                                f.writeText(next, Charsets.UTF_8)
                                "Replaced ${if (bool("replace_all") == true) count else 1} occurrence(s) in ${str("path")}"
                            }
                        }
                    }
                }
                "list_files" -> {
                    val dir = resolve(root, str("path") ?: ".") ?: return "ERROR: path escapes the workspace"
                    if (!dir.isDirectory) return "ERROR: not a directory: ${str("path")}"
                    val recursive = str("recursive")?.toBoolean() ?: false
                    val files = if (recursive) dir.walkTopDown().maxDepth(3).toList()
                    else dir.listFiles()?.toList() ?: emptyList()
                    files.take(400).joinToString("\n") { f ->
                        val rel = f.toRelativeString(root)
                        (if (f.isDirectory) "DIR  " else "FILE ") + rel
                    }.ifBlank { "(empty)" }
                }
                "glob" -> {
                    val pattern = str("pattern") ?: return "ERROR: missing pattern"
                    val dir = resolve(root, str("path") ?: ".") ?: return "ERROR: path escapes the workspace"
                    val regex = globToRegex(pattern)
                    val matches = walk(dir).filter { regex.matches(it.toRelativeString(root).replace('\\', '/')) }
                        .take(200)
                        .sorted()
                    if (matches.isEmpty()) "(no matches for $pattern)" else matches.joinToString("\n") { it.toRelativeString(root).replace('\\', '/') }
                }
                "grep" -> {
                    val pattern = str("pattern") ?: return "ERROR: missing pattern"
                    val pathArg = str("path") ?: "."
                    val target = resolve(root, pathArg) ?: return "ERROR: path escapes the workspace"
                    grep(
                        root = root,
                        pattern = pattern,
                        target = target,
                        pathLabel = pathArg,
                        globFilter = str("glob"),
                        outputMode = str("output_mode") ?: "content",
                        ignoreCase = bool("ignore_case") ?: false,
                        headLimit = int("head_limit") ?: 100
                    )
                }
                "delete_file" -> {
                    val f = resolve(root, str("path") ?: return "ERROR: missing path") ?: return "ERROR: path escapes the workspace"
                    when {
                        f.isFile -> { f.delete(); "Deleted ${str("path")}" }
                        f.isDirectory -> if (f.listFiles()?.isEmpty() == true) { f.delete(); "Deleted empty directory ${str("path")}" } else "ERROR: directory not empty: ${str("path")}"
                        else -> "ERROR: not found: ${str("path")}"
                    }
                }
                "run_command" -> {
                    val command = str("command") ?: return "ERROR: missing command"
                    val timeout = (int("timeout_sec") ?: 30).coerceIn(1, 600)
                    runCommand(command, timeout.toLong())
                }
                "todo_write" -> {
                    val arr = args.get("todos")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?: return "ERROR: missing todos array"
                    val store = todoStoreOverride ?: todo
                    val items: List<TodoStore.TodoItem> = try {
                        gson.fromJson(arr, object : TypeToken<List<TodoStore.TodoItem>>() {}.type)
                    } catch (e: Exception) {
                        return "ERROR: cannot parse todos: ${e.message}"
                    }
                    store.write(items)
                    "TODO list updated (${items.size} items):\n${store.render()}"
                }
                "todo_read" -> (todoStoreOverride ?: todo).render()
                else -> "ERROR: unknown tool `$name`"
            }
        } catch (e: Exception) {
            "ERROR: ${e.javaClass.simpleName}: ${e.message ?: "no message"}"
        }
    }

    override fun execute(name: String, argsJson: String): String = execute(name, argsJson, null)

    /** Pure grep implementation, also reused by the .mjs `ast.findPattern` binding and tests. */
    fun grep(
        root: File,
        pattern: String,
        target: File,
        pathLabel: String,
        globFilter: String?,
        outputMode: String,
        ignoreCase: Boolean,
        headLimit: Int
    ): String {
        val regex = try {
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        } catch (e: Exception) {
            return "ERROR: bad regex: ${e.message}"
        }
        val globRegex = globFilter?.let { globToRegex(it) }
        val files = if (target.isFile) listOf(target)
        else walk(target).filter { f ->
            val rel = f.toRelativeString(root).replace('\\', '/')
            globRegex == null || globRegex.matches(rel)
        }
        val out = StringBuilder()
        var matchedFiles = 0
        var remaining = headLimit.coerceAtLeast(1)
        outer@ for (f in files) {
            if (f.length() > 2_000_000) continue
            var fileHits = 0
            val lines = runCatching { f.readText(Charsets.UTF_8).split('\n') }.getOrNull() ?: continue
            for ((idx, line) in lines.withIndex()) {
                if (line.contains('\u0000')) break@outer // binary guard
                if (!regex.containsMatchIn(line)) continue
                val rel = f.toRelativeString(root).replace('\\', '/')
                when (outputMode) {
                    "files_with_matches" -> {
                        matchedFiles++
                        out.appendLine(rel)
                        break
                    }
                    "count" -> fileHits++
                    else -> {
                        out.appendLine("$rel:${idx + 1}:${line.trimEnd()}")
                        if (--remaining <= 0) break@outer
                    }
                }
            }
            if (outputMode == "count" && fileHits > 0) {
                out.appendLine("${rel(f)}:$fileHits")
                matchedFiles++
            }
        }
        return when {
            out.isBlank() && matchedFiles == 0 -> "(no matches for $pattern in $pathLabel)"
            else -> clip(out.toString().trimEnd('\n'), MAX_OUTPUT)
        }
    }

    private fun rel(f: File): String = f.toRelativeString(root).replace('\\', '/')

    private fun walk(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        val result = ArrayList<File>()
        fun rec(d: File, depth: Int) {
            if (depth > 12 || result.size > 5000) return
            val children = d.listFiles() ?: return
            for (c in children.sortedBy { it.name }) {
                if (c.isDirectory) {
                    if (c.name !in SKIP_DIRS) { result.add(c); rec(c, depth + 1) }
                } else result.add(c)
            }
        }
        rec(dir, 0)
        return result
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) { count++; idx = haystack.indexOf(needle, idx + needle.length) }
        return count
    }

    fun runCommand(command: String, timeoutSec: Long = 30): String {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val parts = if (isWindows) listOf("cmd", "/c", command) else listOf("sh", "-c", command)
        return runCatching {
            val proc = ProcessBuilder(parts)
                .directory(root)
                .redirectErrorStream(true)
                .start()
            // Read raw bytes, not BufferedReader lines: on some JVMs the stream decoder
            // swallows the first byte of piped process output (observed reproducibly on
            // OpenJDK 21.0.13/Debian), which corrupts the very first output line.
            val buf = java.io.ByteArrayOutputStream()
            proc.inputStream.use { input ->
                val chunk = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (buf.size() > MAX_OUTPUT) break
                    buf.write(chunk, 0, n)
                }
            }
            proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            val code = proc.exitValue()
            clip("exit=$code\n${buf.toString(Charsets.UTF_8)}", MAX_OUTPUT)
        }.getOrElse { "ERROR: ${it.javaClass.simpleName}: ${it.message ?: "no message"}" }
    }

    private fun clip(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max) + "\n...(truncated at $max chars)"
}
