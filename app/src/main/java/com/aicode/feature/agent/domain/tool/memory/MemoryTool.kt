package com.aicode.feature.agent.domain.tool.memory

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.memory.MemoryEdit
import com.aicode.feature.agent.domain.memory.MemoryEditResult
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

class MemoryTool @Inject constructor(
    private val memoryRepository: MemoryRepository
) : AbstractContextualTool() {
    private companion object {
        const val TAG = "MemoryTool"
    }

    override val name = "memory"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG, ToolCapability.MODIFY_AGENT_CONFIG)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return when (args["action"]?.jsonPrimitive?.contentOrNull) {
            "read", "list" -> setOf(ToolCapability.READ_AGENT_CONFIG)
            else -> capabilities
        }
    }
    override val description =
        "Manage the AI's long-term memory (read/save/local edit/delete/list). Record new user preferences, project conventions, or architecture decisions when you find them."

    /** Schema for one item in the edits array; semantics match editFile. */
    private val editItemSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "old_string" to mapOf(
                "type" to "string",
                "description" to "Exact text to replace in the current memory body, including indentation and newlines. Include enough context to make it unique."
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

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "Action: read=load memory body; save=create or fully overwrite; edit=locally edit existing body; delete=remove; list=list all memory summaries",
            enum = listOf("read", "save", "edit", "delete", "list"),
            required = true
        ),
        "name" to ToolParameter(
            name = "name",
            type = ParameterType.STRING,
            description = "Short memory name (used as filename, e.g. conventions). Optional for list.",
            required = false
        ),
        "description" to ToolParameter(
            name = "description",
            type = ParameterType.STRING,
            description = "One-line summary (required for save; shown in the system prompt memory list).",
            required = false
        ),
        "content" to ToolParameter(
            name = "content",
            type = ParameterType.STRING,
            description = "Full memory body in Markdown (required for save).",
            required = false
        ),
        "edits" to ToolParameter(
            name = "edits",
            type = ParameterType.ARRAY,
            description = "Edits to apply for action=edit, in order; each matches against the result of the previous. " +
                "A single change is still a one-element array. Each item: {old_string, new_string, replace_all?}.",
            required = false,
            itemsSchema = editItemSchema
        ),
        "scope" to ToolParameter(
            name = "scope",
            type = ParameterType.STRING,
            description = "Scope: project=this project only; global=across projects. Default project.",
            enum = listOf("project", "global"),
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("Missing required parameter: action", "MISSING_ACTION")
        
        val memoryName = args["name"]?.jsonPrimitive?.contentOrNull?.trim()
        val scopeStr = args["scope"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        val scope = if (scopeStr == "global") MemoryScope.GLOBAL else MemoryScope.PROJECT

        return try {
            when (action) {
                "list" -> handleList(context.projectRoot)
                "read" -> handleRead(memoryName, context.projectRoot)
                "save" -> handleSave(args, memoryName, scope, context.projectRoot)
                "edit" -> handleEdit(args, memoryName, scope, context.projectRoot)
                "delete" -> handleDelete(memoryName, scope, context.projectRoot)
                else -> ToolResult.Error("Unsupported action: $action", "UNSUPPORTED_ACTION")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Memory tool failed: ${e.message}", e)
            ToolResult.Error("Memory operation failed: ${e.message}")
        }
    }

    private fun handleList(projectRoot: String?): ToolResult {
        val memories = memoryRepository.listMemories(projectRoot)
        if (memories.isEmpty()) return ToolResult.Success(JsonPrimitive("No memories yet."))
        
        val list = memories.joinToString("\n") { "- ${it.name} (${it.scope.name.lowercase()}): ${it.description}" }
        return ToolResult.Success(JsonPrimitive("Current memories:\n$list"))
    }

    private fun handleRead(name: String?, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("read requires parameter: name", "MISSING_NAME")
        val content = memoryRepository.loadContent(name, projectRoot)
            ?: return ToolResult.Error("Memory \"$name\" not found", "MEMORY_NOT_FOUND")
        return ToolResult.Success(JsonPrimitive(content))
    }

    private fun handleSave(args: Map<String, JsonElement>, name: String?, scope: MemoryScope, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("save requires parameter: name", "MISSING_NAME")
        val description = args["description"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("save requires parameter: description", "MISSING_DESCRIPTION")
        val content = args["content"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("save requires parameter: content", "MISSING_CONTENT")

        if (scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank()) {
            return ToolResult.Error("No workspace selected; cannot save project memory. Use scope=global instead", "NO_WORKSPACE")
        }

        val success = memoryRepository.saveMemory(name, description, content, scope, projectRoot)
        return if (success) {
            ToolResult.Success(JsonPrimitive("Saved memory \"$name\" to ${scope.name.lowercase()} scope. The summary is injected on the next session start. To use it in this session, call read."))
        } else {
            ToolResult.Error("Failed to save memory; see logs.", "SAVE_FAILED")
        }
    }

    private fun handleEdit(args: Map<String, JsonElement>, name: String?, scope: MemoryScope, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("edit requires parameter: name", "MISSING_NAME")

        val edits = parseEdits(args)
            ?: return ToolResult.Error("edit requires parameter: edits", "MISSING_EDITS")

        if (scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank()) {
            return ToolResult.Error("No workspace selected; cannot edit project memory. Use scope=global instead", "NO_WORKSPACE")
        }

        return when (val result = memoryRepository.editMemory(name, edits, scope, projectRoot)) {
            is MemoryEditResult.Success ->
                ToolResult.Success(JsonPrimitive("Edited body of memory \"$name\" (${scope.name.lowercase()} scope)."))
            is MemoryEditResult.NotFound ->
                ToolResult.Error("Memory \"${result.name}\" not found. Create it with save first, or check name and scope.", "MEMORY_NOT_FOUND")
            is MemoryEditResult.Error ->
                ToolResult.Error(result.message, result.code)
        }
    }

    private fun parseEdits(args: Map<String, JsonElement>): List<MemoryEdit>? {
        val arr = args["edits"] as? JsonArray ?: return null
        if (arr.isEmpty()) return null
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val old = obj["old_string"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val new = obj["new_string"]?.jsonPrimitive?.contentOrNull ?: ""
            val all = obj["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
            MemoryEdit(old, new, all)
        }.takeIf { it.isNotEmpty() }
    }

    private fun handleDelete(name: String?, scope: MemoryScope, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("delete requires parameter: name", "MISSING_NAME")
        
        val success = memoryRepository.deleteMemory(name, scope, projectRoot)
        return if (success) {
            ToolResult.Success(JsonPrimitive("Deleted memory \"$name\" from ${scope.name.lowercase()} scope."))
        } else {
            ToolResult.Error("Delete failed; memory \"$name\" may not exist in this scope.", "DELETE_FAILED")
        }
    }
}
