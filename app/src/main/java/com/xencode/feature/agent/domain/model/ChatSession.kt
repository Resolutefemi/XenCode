package com.xencode.feature.agent.domain.model

/**
 * Agent work modes (mirroring Claude Code's Build / Plan / Ask triad):
 * - [BUILD] Build: may read/write files and run commands; whether a permission dialog pops up is decided by settings like the "authorization tier + high-risk rules",
 *   the mode itself no longer bakes in an auto-allow policy (the old AUTO "allow everything" semantics live in the full-permission tier).
 * - [PLAN] Plan: read-only exploration + producing an implementation plan; enters build execution only after the plan-review panel approves.
 * - [ASK] Ask: read-only Q&A, all writes forbidden, never enters the plan-execution flow (matches Claude Code's Ask).
 */
enum class AgentMode {
    BUILD, // Build mode (default): allows every authorized operation; see ToolPermissionPolicyEngine for the exact authorization rules
    PLAN,  // Plan mode: intercepts mutating operations, mostly read-only/exploratory
    ASK    // Ask mode: read-only Q&A; writes are physically blocked, and the AI may not switch to plan mode on its own
}

/**
 * One independent chat session. Messages belong to a session via sessionId; switching sessions switches the chat history.
 */
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val workspacePath: String = "",
    val mode: AgentMode = AgentMode.BUILD,
    /** Mode before entering PLAN (BUILD or ASK); restored when leaving PLAN, null is treated as BUILD. */
    val modeBeforePlan: AgentMode? = null,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    val providerId: String? = null,
    val model: String? = null,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val lastInputTokens: Int = 0,
    val isPinned: Boolean = false,
    /** Subagent session: parent session id; null means a regular root session. */
    val parentId: String? = null,
    /** Subagent session: the spawned subagent's type (e.g. coder / researcher); null means a regular root session. */
    val subagentType: String? = null
)
