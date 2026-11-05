package com.xencode.feature.agent.domain.tool.skill

import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.skill.SkillRepository
import com.xencode.feature.agent.domain.tool.AgentTool
import com.xencode.feature.agent.domain.tool.ParameterType
import com.xencode.feature.agent.domain.tool.ToolParameter
import com.xencode.feature.agent.domain.tool.ToolCapability
import com.xencode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * Lets the AI load the full instruction body of a skill on demand.
 *
 * The system prompt only injects a name+description list of skills (partially omitted under budget limits); once the AI decides a skill applies, it calls this tool to fetch
 * the SKILL.md body (which may contain execution steps such as "first apk add python3, then python ~/.xencode/skills/<name>/x.py"),
 * then acts on that body via read_file/execute_command and other tools. The skill directory in the current execution environment is `~/.xencode/skills/<name>/`.
 *
 * The description wording mirrors the hard requirements of Codex/Claude Code (local field test: with 118 relevant skills in the list, across 239 tool calls
 * loadSkill was used 0 times — soft wording like "use when applicable" provides no constraint):
 * load before acting, announce which skill was used and why, and justify skipping an obviously matching one.
 */
class LoadSkillTool @Inject constructor(
    private val skillRepository: SkillRepository
) : AgentTool() {
    private companion object {
        const val TAG = "LoadSkillTool"
    }

    override val name = "loadSkill"
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)
    override val description =
        "Load the full instruction body (SKILL.md) of a named skill. When the task relates to any skill in the \"available skills\" list, " +
            "you must call this tool first, follow that body strictly this turn, then use other tools; " +
            "state which skill you are using and why before acting; if you skip an obviously matching skill, explain first. " +
            "If the user triggered it with `/skill-name`, the body is already injected this turn; do not load it again."

    override val parameters: Map<String, ToolParameter> = mapOf(
        "skill_name" to ToolParameter(
            name = "skill_name",
            type = ParameterType.STRING,
            description = "Skill name to load (must match a name in the system prompt \"available skills\" list).",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val skillName = args["skill_name"]?.jsonPrimitive?.contentOrNull?.trim()
        if (skillName.isNullOrEmpty()) {
            return ToolResult.Error("Missing required parameter: skill_name", "MISSING_SKILL_NAME")
        }

        val instructions = skillRepository.loadInstructions(skillName)
        if (instructions == null) {
            val available = skillRepository.listSkills().joinToString(", ") { it.name }
            FileLogger.w(TAG, "load_skill not found: $skillName, available: $available")
            return ToolResult.Error(
                "Skill \"$skillName\" not found. Available: ${available.ifEmpty { "(none)" }}",
                "SKILL_NOT_FOUND"
            )
        }

        FileLogger.d(TAG, "load_skill loaded: $skillName (${instructions.length} chars)")
        return ToolResult.Success(JsonPrimitive(instructions))
    }
}
