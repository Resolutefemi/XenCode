package com.xencode.feature.agent.domain.tool.mode

import com.xencode.feature.agent.data.local.dao.ChatSessionDao
import com.xencode.feature.agent.domain.model.AgentContext
import com.xencode.feature.agent.domain.model.AgentMode
import com.xencode.feature.agent.domain.tool.AbstractContextualTool
import com.xencode.feature.agent.domain.tool.ParameterType
import com.xencode.feature.agent.domain.tool.PendingToolPermission
import com.xencode.feature.agent.domain.tool.ToolParameter
import com.xencode.feature.agent.domain.tool.ToolCapability
import com.xencode.feature.agent.domain.tool.ToolPermissionPolicy
import com.xencode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * Lets the AI enter or leave PLAN (planning) mode. On exit, restores the mode from before PLAN was entered (see [AgentContext.modeBeforePlan]).
 *
 * Exiting (action="exit") is not an immediate switch: the workflow suspends and opens a "plan review panel",
 * where the user can choose:
 * - Approve and implement: switch back to the original mode and execute per the plan;
 * - Continue feedback: return to PLAN mode, the user then enters revision comments, the AI revises and exits again to resubmit,
 *   looping until the user is satisfied (aligned with Claude Code's plan-approval loop).
 */
class PlanModeTool @Inject constructor(
    private val chatSessionDao: ChatSessionDao
) : AbstractContextualTool() {

    override val name = "planMode"
    override val description = "Enter or leave PLAN mode. action=\"enter\": switch to read-only exploration and design a complex change; do not write code in BUILD. action=\"exit\": leave PLAN when the plan is ready for review. Exit restores the mode from before PLAN (BUILD→BUILD, ASK→ASK); you do not pick a target mode. Exit opens a plan-review panel: \"Approve and implement\" runs the plan; \"Continue feedback\" stays in PLAN so the user can comment, you revise, and exit again until they approve. Enter and exit both need user authorization. The user switches modes in the UI; this tool cannot change the mode they selected."
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "Action: 'enter' PLAN (read-only planning), 'exit' PLAN (restore the previous mode)",
            required = true,
            enum = listOf("enter", "exit")
        ),
        "reason" to ToolParameter(
            name = "reason",
            type = ParameterType.STRING,
            description = "Reason for entering or leaving PLAN mode; shown to the user",
            required = true
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.Error("Missing required parameter: action", "MISSING_ACTION")

        val reason = args["reason"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("Missing required parameter: reason", "MISSING_REASON")

        val entering = when (action) {
            "enter" -> true
            "exit" -> false
            else -> return ToolResult.Error("Invalid action: $action; must be 'enter' or 'exit'", "INVALID_ACTION")
        }

        if (entering && context.mode == AgentMode.PLAN) {
            return ToolResult.Success(JsonPrimitive("Already in PLAN mode."))
        }
        if (!entering && context.mode != AgentMode.PLAN) {
            return ToolResult.Error("Not in PLAN mode; cannot exit", "NOT_IN_PLAN")
        }

        val sessionId = context.sessionId
            ?: return ToolResult.Error("No session ID; cannot switch mode", "NO_SESSION")

        val sessionEntity = chatSessionDao.getById(sessionId)
            ?: return ToolResult.Error("Session not found", "SESSION_NOT_FOUND")

        // On entering PLAN, record the current mode and restore it on exit: after a planning round trip that entered PLAN from ASK/BUILD,
        // it should return to the original mode rather than always falling back to BUILD. The field is only read while in PLAN and is overwritten on every entry, so no cleanup on exit is needed.
        val effectiveMode: AgentMode
        val modeBeforePlan: String?
        if (entering) {
            effectiveMode = AgentMode.PLAN
            modeBeforePlan = context.mode.name
        } else {
            effectiveMode = sessionEntity.modeBeforePlan
                ?.let { runCatching { AgentMode.valueOf(it) }.getOrNull() }
                ?: AgentMode.BUILD
            modeBeforePlan = sessionEntity.modeBeforePlan
        }

        // Save to the database. The UI layer observes via flow and updates the appearance and the context of subsequent turns automatically.
        chatSessionDao.upsert(sessionEntity.copy(mode = effectiveMode.name, modeBeforePlan = modeBeforePlan))

        return ToolResult.Success(
            JsonPrimitive(
                if (entering) "Entered PLAN mode." else "Exited PLAN mode, restored to ${effectiveMode.name} mode."
            )
        )
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val entering = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.equals("enter", ignoreCase = true) ?: true
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull ?: "no reason"
        val label = if (entering) "enter PLAN mode" else "exit PLAN mode"

        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "Plan mode request",
            summary = "AI requests to $label",
            details = "Action: $label\n\nReason: $reason",
            argsPreview = argsPreview,
            rememberablePatterns = emptyList()
        )
    }
}