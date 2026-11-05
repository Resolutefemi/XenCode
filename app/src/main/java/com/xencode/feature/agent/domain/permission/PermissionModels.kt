package com.xencode.feature.agent.domain.permission

import kotlinx.serialization.Serializable

/**
 * Scope of a tool authorization rule. The scope is not stored on the rule object; it is implied by "which file it lives in":
 * the project file (`.xencode/permissions.json`) or the global file. "Promote to global" in the management UI = delete from the project file, add to the global file.
 */
enum class PermissionScope { PROJECT, GLOBAL }

/**
 * The rule's verdict. The dialog's "Always allow" only ever produces [ALLOW]; [DENY] comes only from the permission config file (the deny list),
 * and during evaluation DENY beats ALLOW across scopes (safety first: a global deny can block a project allow).
 */
enum class PermissionDecision { ALLOW, DENY }

/**
 * The user's choice in the authorization dialog.
 * - [REJECT]: reject this call, not remembered.
 * - [ONCE]: allow this once, not remembered.
 * - [ALWAYS]: allow and remember as a rule (the dialog path always writes to the current project scope).
 */
enum class PermissionChoice { REJECT, ONCE, ALWAYS }

/**
 * One tool authorization rule.
 *
 * @param toolName The tool it applies to, e.g. `Bash`, `writeFile`.
 * @param pattern Match pattern. For shell commands it is a "command prefix", matched by token prefix: subcommand dispatchers record
 *   `git pull` (matches only `git pull ...`, not `git clone`), plain programs record `cat`/`ls` (matching all their invocations);
 *   for non-shell tools it is the wildcard `*` (whole-tool match).
 * @param decision Verdict, defaulting to [PermissionDecision.ALLOW].
 */
@Serializable
data class PermissionRule(
    val toolName: String,
    val pattern: String,
    val decision: PermissionDecision = PermissionDecision.ALLOW
) {
    companion object {
        /** Whole-tool match pattern for non-shell tools. */
        const val WHOLE_TOOL = "*"

        /** Parse a compact-format string into a PermissionRule.
         *  Format examples:
         *  - `"Bash"` → toolName=Bash, pattern=*, ALLOW
         *  - `"Bash(git pull)"` → toolName=Bash, pattern="git pull", ALLOW
         */
        fun fromCompact(compact: String, decision: PermissionDecision = PermissionDecision.ALLOW): PermissionRule {
            val parenIdx = compact.indexOf('(')
            return if (parenIdx >= 0 && compact.endsWith(')')) {
                val toolName = compact.substring(0, parenIdx)
                val pattern = compact.substring(parenIdx + 1, compact.length - 1)
                PermissionRule(toolName, pattern, decision)
            } else {
                PermissionRule(compact, WHOLE_TOOL, decision)
            }
        }
    }

    /** Convert to a compact-format string.
     *  Examples:
     *  - toolName=Bash, pattern=* → `"Bash"`
     *  - toolName=Bash, pattern="git pull" → `"Bash(git pull)"`
     */
    fun toCompact(): String =
        if (pattern == WHOLE_TOOL) toolName else "$toolName($pattern)"
}

// ── Permission file top-level structure ──────────────────────────────────────────────

/**
 * Top-level structure of the permission rules JSON file.
 *
 * Example:
 * ```json
 * {
 *   "permissions": {
 *     "allow": ["Bash(git pull)", "writeFile"],
 *     "deny": ["Bash(rm -rf /)"]
 *   }
 * }
 * ```
 */
@Serializable
data class PermissionFile(
    val permissions: PermissionSection = PermissionSection()
) {
    @Serializable
    data class PermissionSection(
        val allow: List<String> = emptyList(),
        val deny: List<String> = emptyList()
    )

    companion object {
        val EMPTY = PermissionFile()
    }
}

// Conversions between PermissionFile and List<PermissionRule>

/** Parse a [PermissionFile] into a rule list. */
fun PermissionFile.toRuleList(): List<PermissionRule> =
    permissions.allow.map { PermissionRule.fromCompact(it, PermissionDecision.ALLOW) } +
            permissions.deny.map { PermissionRule.fromCompact(it, PermissionDecision.DENY) }

/** Build a [PermissionFile] from a rule list. */
fun List<PermissionRule>.toPermissionFile(): PermissionFile {
    val (allow, deny) = partition { it.decision == PermissionDecision.ALLOW }
    return PermissionFile(
        permissions = PermissionFile.PermissionSection(
            allow = allow.map { it.toCompact() },
            deny = deny.map { it.toCompact() }
        )
    )
}
