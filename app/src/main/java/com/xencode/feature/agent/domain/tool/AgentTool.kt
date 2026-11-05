package com.xencode.feature.agent.domain.tool

import com.xencode.feature.agent.domain.model.AgentImage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
sealed class ToolResult {
    @Serializable
    @SerialName("success")
    data class Success(
        val data: JsonElement,
        val images: List<AgentImage> = emptyList()
    ) : ToolResult()

    @Serializable
    @SerialName("error")
    data class Error(val message: String, val code: String = "UNKNOWN") : ToolResult()

    @Serializable
    @SerialName("partial")
    data class Partial(val data: JsonElement, val message: String) : ToolResult()
}

private val ToolResultTransportJson = Json {
    classDiscriminator = "status"
    encodeDefaults = true
}

fun ToolResult.toTransportString(): String {
    return ToolResultTransportJson.encodeToString(this)
}

data class ToolParameter(
    val name: String,
    val type: ParameterType,
    val description: String,
    val required: Boolean = true,
    val enum: List<String>? = null,
    /**
     * When [type] is [ParameterType.ARRAY], the JSON Schema describing array elements (merged verbatim into
     * the items field for function-calling). For example, when elements are objects pass
     * `{"type":"object","properties":{...},"required":[...]}`. When empty, items is not emitted.
     */
    val itemsSchema: Map<String, Any>? = null
)

enum class ParameterType {
    STRING, INTEGER, BOOLEAN, ARRAY, OBJECT
}

enum class ToolPermissionPolicy {
    AUTO_APPROVE, ASK
}

enum class ToolCapability {
    READ_WORKSPACE,
    WRITE_WORKSPACE,
    EXECUTE_COMMANDS,
    NETWORK_READ,
    NETWORK_WRITE,
    READ_AGENT_CONFIG,
    MODIFY_AGENT_CONFIG,
    MODIFY_CONTAINER_ENV,
    USER_INTERACTION,
    MODIFY_SESSION_STATE,
    MODIFY_TODO_STATE,
    EXTERNAL_TOOL
}

data class PendingToolPermission(
    val id: String,
    val toolName: String,
    val title: String,
    val summary: String,
    val details: String,
    val argsPreview: String,
    /**
     * Pattern list memorized by "Always allow" (for shell commands, the command prefix: a subcommand dispatcher records prefixes with subcommands like `git pull`,
     * plain programs record `cat`/`ls`; non-shell tools use `*`).
     * Empty means this call cannot be memorized (the command cannot be statically judged), and the UI should disable "Always allow" and keep only one-time approval.
     * Filled in after evaluation by [com.xencode.feature.agent.domain.permission.ToolPermissionPolicyEngine].
     */
    val rememberablePatterns: List<String> = emptyList(),
    val rememberDisabledReason: String? = null,
    /** Session id that raised this permission request. With parallel sessions, used by the UI to attribute the dialog and light up the sidebar's awaiting-permission indicator. */
    val sessionId: String = "",
    /**
     * Hazard note from the AI command reviewer under the "autonomous execution" setting (filled when the review rejects or is unsure and defers to the human);
     * when non-null, the dialog must show it prominently to warn the user of the consequences.
     */
    val reviewNote: String? = null
)

abstract class AgentTool {
    abstract val name: String
    abstract val description: String
    abstract val parameters: Map<String, ToolParameter>
    open val permissionPolicy: ToolPermissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    open val capabilities: Set<ToolCapability> = emptySet()

    open fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return capabilities
    }

    abstract suspend fun execute(args: Map<String, JsonElement>): ToolResult

    open suspend fun executeWithContext(args: Map<String, JsonElement>, context: com.xencode.feature.agent.domain.model.AgentContext): ToolResult {
        return execute(args)
    }
    open fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "Confirm tool",
            summary = "AI requests $name",
            details = argsPreview,
            argsPreview = argsPreview
        )
    }

    /**
     * Builds a JSON Schema-compliant parameter description for the function-calling interface actually sent to the LLM
     * (OpenAI's function.parameters / Anthropic's input_schema).
     *
     * Open by design: external tools like MCP carry arbitrary raw inputSchemas that cannot be expressed with the restricted [ParameterType]
     * enum, so they override this method to pass the server-side schema through.
     */
    open fun toJsonSchema(): Map<String, Any> {
        val properties = LinkedHashMap<String, Any>()
        val required = mutableListOf<String>()
        parameters.forEach { (key, param) ->
            val prop = LinkedHashMap<String, Any>()
            prop["type"] = param.type.name.lowercase()
            prop["description"] = param.description
            param.enum?.let { prop["enum"] = it }
            if (param.type == ParameterType.ARRAY) param.itemsSchema?.let { prop["items"] = it }
            properties[key] = prop
            if (param.required) required.add(key)
        }
        return mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required
        )
    }
}

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, JsonElement>
)

/**
 * A tool that can execute in streaming fashion: before the final result, it emits process output step by step (e.g. a command's stdout line by line),
 * so the UI can show "execution progress" instead of only the final result.
 *
 * Implementations should also implement [AgentTool.execute] as a non-streaming fallback; the workflow prefers [executeStream].
 */
interface StreamingAgentTool {
    fun executeStream(args: Map<String, JsonElement>, context: com.xencode.feature.agent.domain.model.AgentContext): Flow<ToolStreamEvent>
}

/** Events produced during streaming tool execution. */
sealed class ToolStreamEvent {
    /** A new chunk of process output (usually one line). */
    data class Progress(val chunk: String) : ToolStreamEvent()
    /** Execution finished, carrying the final aggregated result (fed back to the model). */
    data class Completed(val result: ToolResult) : ToolStreamEvent()
}
