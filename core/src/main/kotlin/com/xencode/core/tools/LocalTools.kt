package com.xencode.core.tools

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Local execution tools for the PC line: file IO plus a sandboxed shell, all
 * rooted at the active workspace. Every tool returns a string (results and
 * errors alike) so the model can read them; errors are prefixed with "ERROR:".
 */
class LocalTools(private val workspaceRoot: File) {

    companion object {
        /** Reject anything that escapes the workspace. */
        fun resolve(root: File, rel: String): File? {
            val cleaned = rel.trim().trimStart('/', '\\')
            if (cleaned.isEmpty()) return root
            val f = File(root, cleaned).normalize()
            return if (f == root || f.startsWith(root)) f else null
        }

        val TOOL_SCHEMAS: List<ToolSchema> = listOf(
            ToolSchema(
                "read_file",
                "Read a UTF-8 text file from the workspace. Returns up to 12000 characters.",
                """{"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative path"}},"required":["path"]}"""
            ),
            ToolSchema(
                "write_file",
                "Create or overwrite a UTF-8 text file in the workspace. Parent directories are created.",
                """{"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative path"},"content":{"type":"string","description":"Full file content"}},"required":["path","content"]}"""
            ),
            ToolSchema(
                "list_files",
                "List entries of a workspace directory (max 400 entries).",
                """{"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative directory (default .)"},"recursive":{"type":"boolean","description":"Also list subdirectories up to depth 3"}},"required":[]}"""
            ),
            ToolSchema(
                "run_command",
                "Run a shell command in the workspace (30s timeout, output capped at 12000 chars). Prefer this for builds, git and searches.",
                """{"type":"object","properties":{"command":{"type":"string","description":"The command line to run"}},"required":["command"]}"""
            )
        )
    }

    data class ToolSchema(val name: String, val description: String, val parametersJson: String)

    private val root: File = workspaceRoot.normalize()

    fun rootDir(): File = root

    fun execute(name: String, argsJson: String): String {
        val args = runCatching {
            com.google.gson.JsonParser.parseString(argsJson.ifBlank { "{}" }).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull() ?: return "ERROR: arguments are not a JSON object"
        val str = { key: String -> args.get(key)?.takeIf { !it.isJsonNull }?.asString }
        return try {
            when (name) {
                "read_file" -> {
                    val f = resolve(root, str("path") ?: return "ERROR: missing path") ?: return "ERROR: path escapes the workspace"
                    if (!f.isFile) return "ERROR: not a file: ${str("path")}"
                    clip(f.readText(Charsets.UTF_8), 12_000)
                }
                "write_file" -> {
                    val f = resolve(root, str("path") ?: return "ERROR: missing path") ?: return "ERROR: path escapes the workspace"
                    f.parentFile?.mkdirs()
                    f.writeText(str("content") ?: "", Charsets.UTF_8)
                    "Wrote ${str("content")?.length ?: 0} chars to ${str("path")}"
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
                "run_command" -> {
                    val command = str("command") ?: return "ERROR: missing command"
                    runCommand(command)
                }
                else -> "ERROR: unknown tool `$name`"
            }
        } catch (e: Exception) {
            "ERROR: ${e.javaClass.simpleName}: ${e.message ?: "no message"}"
        }
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
                    if (buf.size() > 12_000) break
                    buf.write(chunk, 0, n)
                }
            }
            proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            val code = proc.exitValue()
            clip("exit=$code\n${buf.toString(Charsets.UTF_8)}", 12_000)
        }.getOrElse { "ERROR: ${it.javaClass.simpleName}: ${it.message ?: "no message"}" }
    }

    private fun clip(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max) + "\n...(truncated at $max chars)"
}
