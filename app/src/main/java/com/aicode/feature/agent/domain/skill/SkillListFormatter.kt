package com.aicode.feature.agent.domain.skill

/**
 * Formats enabled skills as a system-prompt list with a char budget and stepwise fallback.
 *
 * Why a budget:
 * - Codex (codex-rs/ext/skills): skill list ~2% of context (hard cap 10000 tokens); over budget it
 *   drops descriptions to names-only, then omits the rest and states how many were skipped.
 * - Claude Code: ~1% context budget, 250 chars per description.
 * Local evidence before this: 118 skills = 15441 chars, 53.2% of a 29526-char system prompt, with
 * no cap — and 0 loadSkill calls in 456 messages / 239 tool uses. A compressed list plus mandatory
 * wording plus per-turn hints beats a half-screen dump the model ignores.
 *
 * Fallback order (char budget, greedy in caller-provided order):
 * 1. `- name: description` full entries;
 * 2. remaining skills as `- name` (keep discoverability);
 * 3. omit the rest and append count + where to look them up.
 */
object SkillListFormatter {

    /**
     * List char budget. Conservative between Claude Code 1% and Codex 2%: on a 128K context,
     * 1%≈4000 chars, 2%≈8000 chars (mixed CJK/Latin); take the upper bound. Names-only fallback
     * usually uses less. Change this constant only — format logic and tests are decoupled from it.
     */
    const val DEFAULT_CHAR_BUDGET = 8_000

    /** Slack reserved for the omission line so the notice itself cannot push the list over budget. */
    private const val NOTICE_SLACK = 240

    /** Placeholder when a skill has no description, so every full line has the same shape. */
    private const val NO_DESC = "(no description)"

    /**
     * Format the skill list; empty [skills] returns "".
     * Output stays within [charBudget] (including the omission line), unless a single name already
     * exceeds the remaining budget — then that entry is skipped rather than breaking the cap.
     */
    fun format(skills: List<Skill>, charBudget: Int = DEFAULT_CHAR_BUDGET): String {
        if (skills.isEmpty()) return ""
        if (charBudget <= 0) return ""

        val limit = (charBudget - NOTICE_SLACK).coerceAtLeast(0)
        val full = StringBuilder()
        val namesOnly = StringBuilder()
        var used = 0
        var omitted = 0
        var descPhaseDone = false

        for (skill in skills) {
            val name = skill.name.trim()
            if (name.isEmpty()) continue
            val fullLine = "- $name: ${skill.description.trim().ifEmpty { NO_DESC }}"
            if (!descPhaseDone) {
                val cost = fullLine.length + 1 // +1 = trailing newline
                if (used + cost <= limit) {
                    full.appendLine(fullLine)
                    used += cost
                    continue
                }
                descPhaseDone = true
            }
            val nameLine = "- $name"
            val nameCost = nameLine.length + 1
            if (used + nameCost <= limit) {
                namesOnly.appendLine(nameLine)
                used += nameCost
            } else {
                omitted++
            }
        }

        // If nothing fits (budget below NOTICE_SLACK), a notice-only list is still better than empty.
        if (full.isEmpty() && namesOnly.isEmpty() && omitted == 0) return ""

        return buildString {
            if (full.isNotEmpty()) append(full.trimEnd())
            if (namesOnly.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(namesOnly.trimEnd())
            }
            if (omitted > 0) {
                if (isNotEmpty()) append('\n')
                append(omissionNotice(omitted, charBudget))
            }
        }.trim()
    }

    /** Omission notice: how many were dropped, where to find the full list, and that unlisted skills can still be loaded by name. */
    private fun omissionNotice(omitted: Int, charBudget: Int): String =
        "…$omitted more skill(s) omitted due to prompt budget (max $charBudget chars); " +
            "full list in ~/.aicode/skills/ and <project-root>/.aicode/skills/; try loadSkill by name."
}
