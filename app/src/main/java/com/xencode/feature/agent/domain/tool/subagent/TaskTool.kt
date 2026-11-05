package com.xencode.feature.agent.domain.tool.subagent

import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.data.local.dao.AgentMessageDao
import com.xencode.feature.agent.data.local.dao.ChatSessionDao
import com.xencode.feature.agent.domain.model.AgentContext
import com.xencode.feature.agent.domain.model.ReasoningEffort
import com.xencode.feature.agent.domain.session.SessionUseCase
import com.xencode.feature.agent.domain.subagent.AgentDefinition
import com.xencode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.xencode.feature.agent.domain.subagent.SubAgentEvent
import com.xencode.feature.agent.domain.subagent.SubAgentEventBus
import com.xencode.feature.agent.domain.subagent.SubAgentEventType
import com.xencode.feature.agent.domain.tool.AbstractContextualTool
import com.xencode.feature.agent.domain.tool.ParameterType
import com.xencode.feature.agent.domain.tool.ToolCapability
import com.xencode.feature.agent.domain.tool.ToolParameter
import com.xencode.feature.agent.domain.tool.ToolPermissionPolicy
import com.xencode.feature.agent.domain.tool.ToolResult
import com.xencode.feature.agent.presentation.MessageRole
import com.xencode.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * Subagent tool `task`: uniformly manages the subagent lifecycle.
 *
 * The `action` parameter distinguishes the operations:
 * - `create` (default): creates a subagent session and has the AI message it on the user's behalf; the subagent starts replying automatically.
 * - `send`: sends a message to a given subagent (repeatable). A running subagent receives it piggybacked with the next batch of tool results,
 *   a finished subagent is woken up again; messages are delivered in send order.
 * - `read`: reads the given subagent's last output (its last assistant reply).
 * - `stop`: stops the given subagent's execution (cancels its AI task).
 * - `del`: deletes the given subagent session and all of its messages.
 * - `list`: lists all subagents of the current session with their status.
 *
 * At most 5 concurrently running subagents are allowed (the cap is checked on create).
 * Subagents cannot nest-create subagents (their tool set excludes `task`).
 *
 * `create` accepts an `agent` parameter to pick a custom subagent definition (`agents/<name>.md`),
 * which determines that sub-session's model, tool set, and system prompt; omit it to use the default general subagent that inherits the parent session's model.
 */
class TaskTool @Inject constructor(
    private val sessionUseCase: SessionUseCase,
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val eventBus: SubAgentEventBus,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val aiProviderRepository: AIProviderRepository
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "TaskTool"
        const val TASK_DESCRIPTION_MAX = 30
        /** Type identifier recorded for sub-sessions when no agent is specified. */
        const val DEFAULT_SUBAGENT_TYPE = "subagent"
    }

    override val name = "task"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
        return when (action) {
            "read", "list", "send" -> emptySet()
            else -> setOf(ToolCapability.MODIFY_SESSION_STATE)
        }
    }

    override val description = "Manage subagents: create, send messages, read results, stop, delete, list. Subagents have independent context and full tools, can run in parallel, at most 5 at once. You are notified when they finish; do not poll. Use send to append instructions or follow up on a finished subagent; a running subagent may also message you. create can take agent to pick a custom subagent."

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "Action: create (default, spawn a subagent and run a task) / send (message a subagent, repeatable) / read (last subagent output) / stop (stop execution) / del (delete the subagent session and its messages) / list (list all subagents of this session)",
            required = false
        ),
        "id" to ToolParameter(
            name = "id",
            type = ParameterType.STRING,
            description = "Sub-session id (required for read/send/stop/del; from the id returned by task)",
            required = false
        ),
        "description" to ToolParameter(
            name = "description",
            type = ParameterType.STRING,
            description = "Subagent task description (for create; used as the sub-session title, e.g. \"Fix login bug\")",
            required = false
        ),
        "prompt" to ToolParameter(
            name = "prompt",
            type = ParameterType.STRING,
            description = "Full instructions for the subagent (required for create); becomes its first user message. The subagent sees a fresh context.",
            required = false
        ),
        "agent" to ToolParameter(
            name = "agent",
            type = ParameterType.STRING,
            description = "Custom subagent name (optional for create): a name from the system prompt \"available subagents\" list, using its prompt, model, and tools. Omit to use the default general subagent that inherits this session's model.",
            required = false
        ),
        "message" to ToolParameter(
            name = "message",
            type = ParameterType.STRING,
            description = "Message body for the subagent (required for send). Repeatable; a running subagent receives it soon, a finished one is woken again.",
            required = false
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
        return when (action) {
            "create" -> createSubagent(args, context)
            "send" -> sendToSubagent(args, context)
            "read" -> readSubagent(args, context)
            "stop" -> stopSubagent(args, context)
            "del" -> deleteSubagent(args, context)
            "list" -> listSubagents(context)
            else -> ToolResult.Error("Unknown action: $action; supported: create / send / read / stop / del / list", "INVALID_ARGS")
        }
    }

    /** Creates a subagent and starts its execution. */
    private suspend fun createSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val parentSessionId = context.sessionId ?: return ToolResult.Error("Missing session context", "NO_SESSION")
        val parentSession = sessionUseCase.getSessionById(parentSessionId)
            ?: return ToolResult.Error("Current session not found", "SESSION_NOT_FOUND")

        // Check concurrency cap
        if (eventBus.isFull) {
            return ToolResult.Error(
                "Subagent limit reached (at most ${SubAgentEventBus.MAX_RUNNING} running). Wait for one to finish or stop it before creating another",
                "MAX_SUBAGENTS_REACHED"
            )
        }

        val prompt = (args["prompt"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (prompt.isNullOrBlank()) {
            return ToolResult.Error("Invalid parameter: prompt cannot be empty", "INVALID_ARGS")
        }
        val description = (args["description"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(TASK_DESCRIPTION_MAX)
            ?: "Subagent task"

        // When an agent is specified, its definition must be found: a mistyped name errors out and lists the available names instead of silently falling back to the general subagent,
        // otherwise the whole task would run with the wrong tool set and prompt.
        val agentName = (args["agent"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        var definition: AgentDefinition? = null
        if (agentName != null) {
            definition = agentDefinitionRepository.find(agentName)
            if (definition == null) {
                val available = agentDefinitionRepository.listEnabled().map { it.definition.name }
                val hint = if (available.isEmpty()) "no custom subagents defined" else "available: ${available.joinToString(", ")}"
                return ToolResult.Error("Subagent definition not found: $agentName ($hint)", "AGENT_NOT_FOUND")
            }
        }

        // Create the subagent session
        val subSession = sessionUseCase.newSubSessionEntity(
            title = description,
            parentId = parentSessionId,
            parent = parentSession,
            subagentType = definition?.name ?: DEFAULT_SUBAGENT_TYPE,
            providerId = definition?.providerId?.let { resolveProviderId(it) },
            model = definition?.model,
            reasoningEffort = definition?.reasoningEffort?.let { effort ->
                ReasoningEffort.entries.firstOrNull { it.apiValue == effort }?.name
            },
            mode = definition?.mode
        )
        sessionUseCase.upsertSession(subSession)
        val subSessionId = subSession.id

        // Notify the ViewModel to start the AI workflow on the sub-session
        eventBus.emit(
            SubAgentEvent(
                subSessionId = subSessionId,
                parentSessionId = parentSessionId,
                type = SubAgentEventType.SPAWNED,
                detail = prompt
            )
        )
        FileLogger.i(TAG, "subagent created: session=$subSessionId parent=$parentSessionId agent=${definition?.name ?: "-"}")

        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "running")
                definition?.let { put("agent", it.name) }
                put("message", "Subagent created and started. You will be notified when it finishes. Use task(action=\"read\", id=...) to read output, task(action=\"stop\", id=...) to stop it.")
            }
        )
    }

    /**
     * Sends a message to the given subagent (repeatable).
     *
     * The event is dispatched by the ViewModel according to the recipient's state: a running subagent queues the message and receives it piggybacked with the next batch of tool results;
     * a finished subagent is woken up again to start a new turn. No direct delivery in the tool layer, to reuse the same busy/idle dispatch logic.
     */
    private suspend fun sendToSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val parentSessionId = context.sessionId ?: return ToolResult.Error("Missing session context", "NO_SESSION")
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("Invalid parameter: id cannot be empty", "INVALID_ARGS")
        }
        val message = (args["message"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (message.isNullOrBlank()) {
            return ToolResult.Error("Invalid parameter: message cannot be empty", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("Sub-session not found: $subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != parentSessionId) {
            return ToolResult.Error("Can only message subagents spawned by the current session", "NOT_YOUR_SUBAGENT")
        }

        eventBus.emit(
            SubAgentEvent(
                subSessionId = subSessionId,
                parentSessionId = parentSessionId,
                type = SubAgentEventType.MESSAGE_FROM_PARENT,
                detail = message
            )
        )
        FileLogger.i(TAG, "sent message to subagent: session=$subSessionId parent=$parentSessionId")
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "delivered")
                put("message", "Message delivered to the subagent. A running one receives it with the next tool results; a finished one is woken again. You can keep using send.")
            }
        )
    }

    /**
     * Resolves the definition's provider field into a real provider id: first an exact match by id, then a case-insensitive match by name,
     * so users can write the provider name shown in settings directly in the frontmatter. Returns null if neither matches (inherit the parent session).
     */
    private suspend fun resolveProviderId(raw: String): String? {
        aiProviderRepository.getProviderById(raw)?.let { return it.id }
        val all = aiProviderRepository.getAllProviders().first()
        return all.firstOrNull { it.name.equals(raw, ignoreCase = true) }?.id
    }

    /** Reads the given subagent's last output. */
    private suspend fun readSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("Invalid parameter: id cannot be empty", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("Sub-session not found: $subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("Can only read subagents spawned by the current session", "NOT_YOUR_SUBAGENT")
        }

        val messages = agentMessageDao.getMessagesBySessionOnce(subSessionId)
        // Take the last assistant reply with content (skipping reasoning-only intermediate messages)
        val lastAssistant = messages.lastOrNull {
            it.role == MessageRole.ASSISTANT.name && it.content.isNotBlank()
        }
        val fallback = messages.lastOrNull { it.role == MessageRole.USER.name }
        val content = when {
            lastAssistant != null -> lastAssistant.content
            fallback != null -> "(Subagent has not replied yet) request: ${fallback.content.take(500)}"
            else -> "(Subagent session is empty)"
        }
        val last = runCatching { messages.lastOrNull()?.timestamp ?: 0L }.getOrDefault(0L)
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("title", sub.title)
                put("updatedAt", last)
                put("lastOutput", content)
            }
        )
    }

    /** Stops the given subagent's execution. */
    private suspend fun stopSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("Invalid parameter: id cannot be empty", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("Sub-session not found: $subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("Can only stop subagents spawned by the current session", "NOT_YOUR_SUBAGENT")
        }

        eventBus.emit(
            SubAgentEvent(
                subSessionId = subSessionId,
                parentSessionId = context.sessionId!!,
                type = SubAgentEventType.STOPPED
            )
        )
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "stopping")
                put("message", "Stop requested; cancelling the subagent's AI task.")
            }
        )
    }

    /** Deletes the given subagent session (including its messages). Requests a stop first if still running. */
    private suspend fun deleteSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("Invalid parameter: id cannot be empty", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("Sub-session not found: $subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("Can only delete subagents spawned by the current session", "NOT_YOUR_SUBAGENT")
        }
        if (eventBus.activeSubSessionIds.value.contains(subSessionId)) {
            eventBus.emit(
                SubAgentEvent(
                    subSessionId = subSessionId,
                    parentSessionId = context.sessionId!!,
                    type = SubAgentEventType.STOPPED
                )
            )
        }
        sessionUseCase.deleteSession(subSessionId)
        FileLogger.i(TAG, "subagent deleted: session=$subSessionId parent=${context.sessionId}")
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "deleted")
                put("message", "Subagent deleted (including all of its messages).")
            }
        )
    }

    /** Lists all subagents of the current session. */
    private suspend fun listSubagents(context: AgentContext): ToolResult {
        val parentSessionId = context.sessionId ?: return ToolResult.Error("Missing session context", "NO_SESSION")
        val subs = chatSessionDao.getSubSessionsByParentOnce(parentSessionId)
        val activeIds = eventBus.activeSubSessionIds.value

        val jsonArray = buildJsonArray {
            subs.forEach { entity ->
                addJsonObject {
                    put("id", entity.id)
                    put("title", entity.title)
                    put("state", if (entity.id in activeIds) "running" else "completed")
                    put("createdAt", entity.createdAt)
                    put("updatedAt", entity.updatedAt)
                }
            }
        }

        return ToolResult.Success(
            buildJsonObject {
                put("subagents", jsonArray)
                put("count", subs.size)
                put("runningCount", activeIds.size)
                put("maxRunning", SubAgentEventBus.MAX_RUNNING)
            }
        )
    }
}