package com.xencode.feature.agent.domain.subagent

import com.xencode.feature.agent.domain.model.AgentMode

/** Source scope of a sub-agent definition: global (shared across projects) or project-level (effective only in the current workspace, git-trackable). */
enum class AgentDefinitionScope { GLOBAL, PROJECT }

/**
 * Injectable fragments of the sub-agent system prompt. Omitting `inject` uses [AgentDefinition.DEFAULT_INJECT].
 *
 * BASE and MAIN_RULES are not strictly mutually exclusive; writing both injects both (MAIN_RULES first).
 */
enum class InjectPart(val token: String) {
    /** Compact sub-agent baseline (`agent/subagent-base.md`): tool usage, path conventions, safety boundaries. */
    BASE("base"),

    /** The main agent's full static rule baseline (all `00`~`70` fragments), used when a sub-agent must behave exactly like the main agent. */
    MAIN_RULES("mainRules"),

    /** Available skills list (the sub-agent can loadSkill). */
    SKILLS("skills"),

    /** Summary list of global + project memories. */
    MEMORY("memory"),

    /** Project rules (AGENTS.md / CLAUDE.md). */
    PROJECT_RULES("projectRules");

    companion object {
        /** Lenient token mapping for frontmatter: ignores case and hyphen/underscore differences. */
        fun fromToken(token: String): InjectPart? {
            val normalized = token.trim().lowercase().replace("-", "").replace("_", "")
            return when (normalized) {
                "base" -> BASE
                "mainrules" -> MAIN_RULES
                "skills", "skill" -> SKILLS
                "memory", "memories" -> MEMORY
                "projectrules", "projectrule" -> PROJECT_RULES
                else -> null
            }
        }
    }
}

/**
 * A custom sub-agent definition, from the frontmatter + body of `agents/<name>.md`.
 *
 * @param name Unique identifier; `task(agent="<name>")` dispatches with it
 * @param description When to dispatch this sub-agent; injected into the main agent's prompt for selection
 * @param providerId Bound provider id; null means inherit from the parent session
 * @param model Bound model name; null means inherit from the parent session
 * @param reasoningEffort Reasoning effort (low/medium/high); null means inherit from the parent session
 * @param mode Run mode (build/plan/auto); null means inherit from the parent session
 * @param allowedTools Tool allowlist; empty means inherit the full tool set
 * @param disallowedTools Tool blocklist, applied before the allowlist
 * @param inject Prompt fragments to inject
 * @param prompt The agent's own system prompt (body)
 * @param filePath Container path of the definition file, shown and deleted on the settings page
 */
data class AgentDefinition(
    val name: String,
    val description: String,
    val providerId: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val mode: AgentMode? = null,
    val allowedTools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),
    val inject: Set<InjectPart> = DEFAULT_INJECT,
    val prompt: String,
    val filePath: String? = null
) {
    /**
     * Trims the tool-name set by allowlist/blocklist. `task` is always removed — sub-agents must not nest-dispatch sub-agents.
     *
     * The blocklist applies first, then intersects with the allowlist; `mcp__server__*` wildcards are supported
     * to disable all tools of an MCP server at once.
     */
    fun filterToolNames(all: List<String>): List<String> {
        val afterDeny = all.filterNot { name -> disallowedTools.any { matches(it, name) } }
        val afterAllow = if (allowedTools.isEmpty()) {
            afterDeny
        } else {
            afterDeny.filter { name -> allowedTools.any { matches(it, name) } }
        }
        return afterAllow.filterNot { it == NESTED_TOOL }
    }

    private fun matches(pattern: String, toolName: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty()) return false
        if (p.endsWith("*")) return toolName.startsWith(p.dropLast(1), ignoreCase = true)
        return toolName.equals(p, ignoreCase = true)
    }

    companion object {
        /** Default injection when `inject` is omitted: compact baseline + skills + memory + project rules. */
        val DEFAULT_INJECT: Set<InjectPart> = setOf(
            InjectPart.BASE,
            InjectPart.SKILLS,
            InjectPart.MEMORY,
            InjectPart.PROJECT_RULES
        )

        /** Sub-agents must not nest-dispatch; their tool set always drops this tool. */
        const val NESTED_TOOL = "task"

        /** Tool only sub-agents may use (child-to-parent messaging); the main session's tool set drops it. */
        const val PARENT_MESSAGE_TOOL = "messageParent"
    }
}

/** One effective sub-agent definition entry: the definition itself + its source scope, for the UI to label "global/project". */
data class AgentDefinitionEntry(
    val definition: AgentDefinition,
    val scope: AgentDefinitionScope
)

/** Form snapshot submitted when creating/editing a sub-agent on the settings page. */
data class AgentDefinitionForm(
    val name: String,
    val description: String,
    val providerId: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val mode: AgentMode? = null,
    val allowedTools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),
    val inject: Set<InjectPart> = AgentDefinition.DEFAULT_INJECT,
    val prompt: String = ""
)

/** Reasons saving a sub-agent definition can fail. */
enum class AgentSaveError {
    /** Name is empty, too long, or contains characters unusable in a file name. */
    INVALID_NAME,

    /** Prompt body is empty: the parser drops such definitions outright, so saving them is useless. */
    EMPTY_PROMPT,

    /** A sub-agent with the same name already exists in this scope. */
    NAME_CONFLICT,

    /** Disk write failed. */
    IO_FAILED
}
