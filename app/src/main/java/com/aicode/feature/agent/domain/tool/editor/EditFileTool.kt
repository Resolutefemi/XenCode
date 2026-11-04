package com.aicode.feature.agent.domain.tool.editor

import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.core.util.FileLogger
import com.aicode.core.util.LineDiff
import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

private const val TAG = "EditFileTool"

/**
 * Exact string-match file editor, replacing the old line-number insert/replace/delete trio.
 *
 * String match is used instead of line numbers because the first edit shifts every later
 * line number. Matching on content itself covers three operations in one tool:
 *   - replace: old_string -> new_string
 *   - delete: empty new_string
 *   - insert: replace a span with itself plus the new content
 *
 * One call can apply several edits to the same file: pass an edits array, applied in order,
 * each matching against the result of the previous. The batch is all-or-nothing — if any
 * edit fails (no match, or multiple matches without replace_all), nothing is written.
 *
 * For safety, each old_string must be unique in the current content unless replace_all=true
 * or a longer unique context is provided.
 */
class EditFileTool @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AgentTool() {
    override val name = "editFile"
    override val description =
        "Edit an existing file by exact string match. Preferred for local edits. edits can apply several changes in one atomic batch (any failure rolls all back). Use writeFile to rewrite a whole file."
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.WRITE_WORKSPACE)

    /** Schema for one item in the edits array, used as the function-calling items schema. */
    private val editItemSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "old_string" to mapOf(
                "type" to "string",
                "description" to "Exact text to replace, including indentation and newlines. Include enough context to make it unique."
            ),
            "new_string" to mapOf(
                "type" to "string",
                "description" to "Replacement text. Empty string deletes the matched span."
            ),
            "replace_all" to mapOf(
                "type" to "boolean",
                "description" to "Replace every occurrence of this old_string. Default false (unique match required)."
            )
        ),
        "required" to listOf("old_string", "new_string")
    )

    override val parameters = mapOf(
        "path" to ToolParameter("path", ParameterType.STRING, "File path: ~/workspace/... for project files; other absolute paths (e.g. /etc/..., /root/...) for container system files.", required = true),
        "edits" to ToolParameter(
            "edits",
            ParameterType.ARRAY,
            "Edits to apply in order; each matches against the result of the previous. " +
                "A single change is still a one-element array. Each item: {old_string, new_string, replace_all?}.",
            required = true,
            itemsSchema = editItemSchema
        )
    )

    /** One parsed edit. */
    private data class Edit(val oldString: String, val newString: String, val replaceAll: Boolean)

    /** Diff of one applied edit, for UI rendering and feeding back to the model. */
    private data class Hunk(val startLine: Int, val added: Int, val removed: Int, val diff: String)

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val path = args["path"]?.jsonPrimitive?.contentOrNull ?: "unknown file"
        val editCount = (args["edits"] as? JsonArray)?.size ?: 1
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "Confirm edit file",
            summary = "AI requests editing $path",
            details = "Edits: $editCount\nEach edit must match fully; if any fails, nothing is written.",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val path = args["path"]?.jsonPrimitive?.contentOrNull
                ?: return ToolResult.Error("Missing required parameter: path", "MISSING_PATH")

            val edits = parseEdits(args)
                ?: return ToolResult.Error("Missing required parameter: edits", "MISSING_EDITS")

            // Validate each edit (empty old_string / no-op) before touching the file.
            edits.forEachIndexed { i, e ->
                if (e.oldString.isEmpty()) {
                    return ToolResult.Error("Edit ${i + 1}: old_string cannot be empty; use writeFile to create a file", "EMPTY_OLD_STRING")
                }
                if (e.oldString == e.newString) {
                    return ToolResult.Error("Edit ${i + 1}: old_string and new_string are identical; nothing to change", "NO_OP")
                }
            }

            FileLogger.d(TAG, "edit_file path=$path (edits=${edits.size})")
            if (!fileAccess.exists(path)) {
                FileLogger.w(TAG, "edit_file file not found: $path")
                return ToolResult.Error("File not found: $path", "FILE_NOT_FOUND")
            }

            // Apply all edits in memory first; on any failure return immediately and never write (all-or-nothing).
            var content = fileAccess.readFile(path)
            val hunks = ArrayList<Hunk>(edits.size)
            var totalReplacements = 0

            edits.forEachIndexed { i, e ->
                val occurrences = content.split(e.oldString).size - 1
                if (occurrences == 0) {
                    FileLogger.w(TAG, "edit_file edit ${i + 1} did not match: $path")
                    return ToolResult.Error(
                        "Edit ${i + 1}: old_string was not found in the file. Confirm the text (including indentation/newlines) matches the current file exactly",
                        "NO_MATCH"
                    )
                }
                if (occurrences > 1 && !e.replaceAll) {
                    FileLogger.w(TAG, "edit_file edit ${i + 1} matched $occurrences times without replace_all: $path")
                    return ToolResult.Error(
                        "Edit ${i + 1}: old_string matched $occurrences places in the file. Provide a longer unique context, or set replace_all=true for this edit",
                        "MULTIPLE_MATCHES"
                    )
                }

                // 1-based start line in the content before this edit is applied. Content already
                // includes prior edits, so the line number reflects earlier line drift.
                val matchIndex = content.indexOf(e.oldString)
                val startLine = if (matchIndex >= 0) content.substring(0, matchIndex).count { it == '\n' } + 1 else 1

                val diff = LineDiff.toUnified(e.oldString, e.newString)
                val added = diff.lines().count { it.startsWith("+") }
                val removed = diff.lines().count { it.startsWith("-") }
                hunks.add(Hunk(startLine = startLine, added = added, removed = removed, diff = diff))

                content = if (e.replaceAll) content.replace(e.oldString, e.newString)
                else content.replaceFirst(e.oldString, e.newString)
                totalReplacements += if (e.replaceAll) occurrences else 1
            }

            fileAccess.writeFile(path, content, overwrite = true)

            val addedTotal = hunks.sumOf { it.added }
            val removedTotal = hunks.sumOf { it.removed }
            val hunksJson = JsonArray(hunks.map { h ->
                JsonObject(mapOf(
                    "start_line" to JsonPrimitive(h.startLine),
                    "added" to JsonPrimitive(h.added),
                    "removed" to JsonPrimitive(h.removed),
                    "diff" to JsonPrimitive(h.diff)
                ))
            })

            FileLogger.v(TAG, "edit_file ok path=$path edits=${edits.size} replacements=$totalReplacements")
            ToolResult.Success(
                JsonObject(mapOf(
                    "status" to JsonPrimitive("edited"),
                    "path" to JsonPrimitive(fileAccess.toDisplayPath(path)),
                    "edits_count" to JsonPrimitive(edits.size),
                    "replacements" to JsonPrimitive(totalReplacements),
                    "total_lines" to JsonPrimitive(content.lines().size),
                    "added_lines" to JsonPrimitive(addedTotal),
                    "removed_lines" to JsonPrimitive(removedTotal),
                    "hunks" to hunksJson
                ))
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "edit_file exception", e)
            ToolResult.Error(e.message ?: "Failed to edit file", "EDIT_ERROR")
        }
    }

    /**
     * Parse the edits array.
     */
    private fun parseEdits(args: Map<String, JsonElement>): List<Edit>? {
        val arr = args["edits"] as? JsonArray ?: return null
        if (arr.isEmpty()) return null
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val old = obj["old_string"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val new = obj["new_string"]?.jsonPrimitive?.contentOrNull ?: ""
            val all = obj["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
            Edit(old, new, all)
        }.takeIf { it.isNotEmpty() }
    }
}
