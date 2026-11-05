package com.xencode.feature.agent.domain.permission

import kotlinx.serialization.Serializable

/**
 * High-risk interception rules: under the "rule-based interception" tier, any shell command matching an enabled rule must go to human confirmation (ASK).
 *
 * Unlike authorization rules ([PermissionRule]): authorization rules come from "always allow/deny" memories and decide allow/forbid;
 * high-risk rules are system presets the user can edit, and their verdict is "a human must look at this" — they never produce an allow.
 *
 * Matching:
 * - [HighRiskMatchType.PREFIX]: after the command is split into top-level segments, any segment hitting the token prefix (reusing [ShellCommandParser.matches],
 *   e.g. `rm -rf` matches `rm -rf /data` but not `rm -rfd`);
 * - [HighRiskMatchType.REGEX]: the regex is tried against the whole command line and against each top-level segment (joined back verbatim),
 *   so `^`/`$` anchors work at segment level too (e.g. `curl … | sh` pipes, writing block devices).
 */
@Serializable
data class HighRiskRule(
    /** Stable id: preset rules start with `preset.`; user-created rules use a generated timestamp. */
    val id: String,
    val pattern: String,
    val matchType: HighRiskMatchType = HighRiskMatchType.PREFIX,
    /** Category name (UI grouping badge), e.g. "Delete and erase". */
    val category: String,
    /** Human-readable match explanation, shown in the dialog and the list. */
    val description: String = "",
    val enabled: Boolean = true,
    /** Whether it is a system preset: presets can be restored via "Reset to defaults"; restoring after deletion brings them back enabled. */
    val builtin: Boolean = true
)

enum class HighRiskMatchType { PREFIX, REGEX }

/** High-risk rule matching: returns the first enabled rule that hits, or null when none does. */
object HighRiskRuleMatcher {

    fun matchAny(rules: List<HighRiskRule>, command: String): HighRiskRule? {
        val analysis = ShellCommandParser.analyze(command)
        for (rule in rules) {
            if (!rule.enabled) continue
            when (rule.matchType) {
                HighRiskMatchType.PREFIX -> {
                    if (analysis.segments.any { ShellCommandParser.matches(rule.pattern, it) }) return rule
                }
                HighRiskMatchType.REGEX -> {
                    val regex = runCatching { Regex(rule.pattern) }.getOrNull() ?: continue
                    if (regex.containsMatchIn(command)) return rule
                    if (analysis.segments.any { regex.containsMatchIn(it.joinToString(" ")) }) return rule
                }
            }
        }
        return null
    }
}
