package com.xencode.feature.agent.domain.tool.file

import com.xencode.feature.agent.domain.tool.AgentTool
import com.xencode.feature.agent.domain.tool.ParameterType
import com.xencode.feature.agent.domain.tool.PendingToolPermission
import com.xencode.feature.agent.domain.tool.ToolParameter
import com.xencode.feature.agent.domain.tool.ToolCapability
import com.xencode.feature.agent.domain.tool.ToolPermissionPolicy
import com.xencode.feature.agent.domain.tool.ToolResult
import com.xencode.core.util.FileLogger
import com.xencode.core.util.LineDiff
import com.xencode.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

private const val TAG = "FileTools"

class ReadFileTool @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AgentTool() {
    override val name = "readFile"
    override val description = "Read a file (workspace file or container absolute path). Each read has line and byte limits; continue with start_line if truncated."
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
    override val parameters = mapOf(
        "path" to ToolParameter("path", ParameterType.STRING, "File path: ~/workspace/... for project files; other absolute paths (e.g. /etc/..., /root/...) for container system files.", required = true),
        "start_line" to ToolParameter("start_line", ParameterType.INTEGER, "Start line (1-based).", required = false),
        "end_line" to ToolParameter("end_line", ParameterType.INTEGER, "End line; span from start_line is at most 2000 lines, truncated beyond that.", required = false)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val path = args["path"]?.jsonPrimitive?.contentOrNull ?: run {
                FileLogger.w(TAG, "read_file missing path")
                return ToolResult.Error("Missing required parameter: path", "MISSING_PATH")
            }
            FileLogger.d(TAG, "read_file path=$path")

            if (!fileAccess.exists(path)) {
                FileLogger.w(TAG, "read_file file not found: $path")
                return ToolResult.Error("File not found: $path", "FILE_NOT_FOUND")
            }

            val startLine = args["start_line"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 1
            val requestedEnd = args["end_line"]?.jsonPrimitive?.intOrNull
            // Upper bound of the line window: the smaller of the explicit end_line and start_line+MAX_LINES; MAX_LINES cap when absent.
            val lineCap = startLine + MAX_LINES - 1
            val endCap = if (requestedEnd != null) minOf(requestedEnd, lineCap) else lineCap

            // Read line by line in streaming fashion to avoid readText() loading the whole file into memory and causing OOM on mobile;
            // keep only the [startLine, endCap] window, and stop early once accumulated bytes exceed MAX_BYTES.
            // Runs on the IO dispatcher: neither local File reads nor blocking reads of remote exec streams should occupy the caller's thread.
            val sb = StringBuilder()
            var totalLines = 0
            var emittedLines = 0
            var byteCount = 0
            var truncatedByBytes = false
            var lineNo = 0
            withContext(Dispatchers.IO) {
                fileAccess.readLines(path).forEach { line ->
                    lineNo++
                    totalLines = lineNo
                    if (lineNo < startLine) return@forEach
                    if (lineNo > endCap) {
                        // Past the window, but keep counting to get an accurate total_lines.
                        return@forEach
                    }
                    if (!truncatedByBytes) {
                        val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
                        if (byteCount + lineBytes > MAX_BYTES && emittedLines > 0) {
                            truncatedByBytes = true
                        } else {
                            if (emittedLines > 0) sb.append('\n')
                            sb.append(line)
                            byteCount += lineBytes
                            emittedLines++
                        }
                    }
                }
            }

            val lastEmittedLine = startLine + emittedLines - 1
            // The window end line the user wanted: min(end_line, EOF) if end_line was given, otherwise EOF.
            // We only emitted up to lastEmittedLine; if it falls before the wanted window end, the content was truncated and more remains.
            val wantedEnd = if (requestedEnd != null) minOf(requestedEnd, totalLines) else totalLines
            val truncated = emittedLines > 0 && lastEmittedLine < wantedEnd
            val note = when {
                !truncated -> null
                truncatedByBytes -> "Content exceeded the ${MAX_BYTES / 1024}KB limit and was truncated. Continue with start_line=${lastEmittedLine + 1}."
                else -> "Content exceeded the $MAX_LINES-line limit and was truncated. Continue with start_line=${lastEmittedLine + 1}."
            }

            FileLogger.v(TAG, "read_file ok path=$path total=$totalLines emitted=$emittedLines bytes=$byteCount truncated=$truncated")
            val resultMap = mutableMapOf<String, JsonElement>(
                "content" to JsonPrimitive(sb.toString()),
                "total_lines" to JsonPrimitive(totalLines),
                "start_line" to JsonPrimitive(startLine),
                "end_line" to JsonPrimitive(maxOf(lastEmittedLine, startLine - 1)),
                "read_lines" to JsonPrimitive(emittedLines),
                "truncated" to JsonPrimitive(truncated)
            )
            if (note != null) resultMap["note"] = JsonPrimitive(note)
            ToolResult.Success(JsonObject(resultMap))
        } catch (e: Exception) {
            FileLogger.e(TAG, "read_file exception", e)
            ToolResult.Error(e.message ?: "Failed to read file", "READ_ERROR")
        }
    }

    private companion object {
        /** Maximum lines returned per call; excess is truncated with a hint to continue reading with start_line. */
        const val MAX_LINES = 2000
        /** Maximum bytes returned per call (UTF-8, ~200KB), preventing oversized lines from blowing up memory/context. */
        const val MAX_BYTES = 200 * 1024
    }
}

/**
 * Writes an entire file, creating it (and parent directories) automatically if missing.
 *
 * The overwrite parameter absorbs the old create_file's "do not overwrite existing files" semantics:
 * overwrite=false errors if the target already exists, usable for safely creating new files.
 */
class WriteFileTool @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AgentTool() {
    override val name = "writeFile"
    override val description = "Write a full file; creates the file and parent dirs if missing. Use editFile for local edits."
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.WRITE_WORKSPACE)
    override val parameters = mapOf(
        "path" to ToolParameter("path", ParameterType.STRING, "File path: ~/workspace/... for project files; other absolute paths (e.g. /etc/..., /root/...) for container system files.", required = true),
        "content" to ToolParameter("content", ParameterType.STRING, "File content", required = true),
        "overwrite" to ToolParameter("overwrite", ParameterType.BOOLEAN, "Overwrite if the target exists. Default true; false errors if the file already exists.", required = false)
    )

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val path = args["path"]?.jsonPrimitive?.contentOrNull ?: "unknown file"
        val content = args["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val overwrite = args["overwrite"]?.jsonPrimitive?.booleanOrNull ?: true
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "Confirm write file",
            summary = "AI requests writing $path",
            details = "Characters: ${content.length}\nLines: ${content.lines().size}\nOverwrite: ${if (overwrite) "yes" else "no"}",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val path = args["path"]?.jsonPrimitive?.contentOrNull ?: run {
                FileLogger.w(TAG, "write_file missing path")
                return ToolResult.Error("Missing required parameter: path", "MISSING_PATH")
            }
            val content = args["content"]?.jsonPrimitive?.contentOrNull ?: ""
            val overwrite = args["overwrite"]?.jsonPrimitive?.booleanOrNull ?: true

            FileLogger.d(TAG, "write_file path=$path (${content.length} chars, overwrite=$overwrite)")
            val existed = fileAccess.exists(path)
            if (existed && !overwrite) {
                FileLogger.w(TAG, "write_file exists and overwrite=false: $path")
                return ToolResult.Error("File already exists: $path (overwrite=false)", "FILE_EXISTS")
            }

            // Keep the old content before writing to generate the "old → new" diff (same shape as edit_file; the UI renders a colored diff from it).
            val oldContent = if (existed) runCatching { fileAccess.readFile(path) }.getOrDefault("") else ""

            fileAccess.writeFile(path, content, overwrite = true)

            // Build the unified diff text: newly created files are presented as "all added" (old content treated as empty, avoiding a fake deletion line);
            // overwrites compute line-level additions/removals from old to new. LineDiff uses O(n·m) memory, so oversized rewrites skip LCS
            // and degrade to all-added, preventing OOM on mobile from building the DP table.
            val diff = buildWriteDiff(existed, oldContent, content)
            val added = diff.lines().count { it.startsWith("+") }
            val removed = diff.lines().count { it.startsWith("-") }
            val hunksJson = JsonArray(listOf(
                JsonObject(mapOf(
                    "start_line" to JsonPrimitive(1),
                    "added" to JsonPrimitive(added),
                    "removed" to JsonPrimitive(removed),
                    "diff" to JsonPrimitive(diff)
                ))
            ))

            FileLogger.v(TAG, "write_file ok path=$path created=${!existed} lines=${content.lines().size} (+$added -$removed)")
            ToolResult.Success(
                JsonObject(mapOf(
                    "path" to JsonPrimitive(fileAccess.toDisplayPath(path)),
                    "created" to JsonPrimitive(!existed),
                    "bytes_written" to JsonPrimitive(content.length),
                    "lines_written" to JsonPrimitive(content.lines().size),
                    "added_lines" to JsonPrimitive(added),
                    "removed_lines" to JsonPrimitive(removed),
                    "hunks" to hunksJson
                ))
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "write_file exception", e)
            ToolResult.Error(e.message ?: "Failed to write file", "WRITE_ERROR")
        }
    }

    /**
     * Builds the unified diff text for write_file (each line starts with `+`/`-`/` `):
     * - New file: old content treated as empty, the whole file presented as "all added", no LCS;
     * - Overwrite of manageable size: computes the old → new line-level LCS diff;
     * - Overwrite where either side exceeds [MAX_DIFF_LINES]: skips the O(n·m) LCS (easily OOM on mobile),
     *   degrading to "all added" and showing only the content written to disk.
     */
    private fun buildWriteDiff(existed: Boolean, oldContent: String, newContent: String): String {
        val newLines = newContent.split("\n")
        if (!existed) return newLines.joinToString("\n") { "+$it" }
        val oldLines = oldContent.split("\n")
        if (maxOf(oldLines.size, newLines.size) > MAX_DIFF_LINES) {
            FileLogger.w(TAG, "write_file too large, skip LCS diff (old=${oldLines.size}, new=${newLines.size})")
            return newLines.joinToString("\n") { "+$it" }
        }
        return LineDiff.toUnified(oldContent, newContent)
    }

    private companion object {
        /** Skip LCS when either the old or new side exceeds this many lines: the DP table is O(n·m) ints, too large would overwhelm mobile memory. */
        const val MAX_DIFF_LINES = 2000
    }
}
