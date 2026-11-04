package com.aicode.feature.agent.domain.skill

/**
 * Per turn, match skills that may relate to the user input and append a short hint to the latest user message.
 *
 * Why: Claude Code injects "Skills relevant to your task:" every turn — the list appears once in the
 * system prompt and the model forgets; pushing the top matches (name + description) at the decision
 * point is what keeps skill-call rates high. Codex uses list budget + mandatory wording for the same.
 *
 * Matching is a local heuristic, not exhaustive: [MIN_SCORE] requires several trigger hits.
 * The hint tells the model to ignore this block if none apply, so false positives are cheap.
 *
 * Trigger extraction (same rules on user text and skills):
 * - CJK: consecutive Han pairs (bigrams), e.g. 刷机, 救砖;
 * - Latin/digits: tokens of length ≥ 3, lowercased, e.g. root, adb, ssh, pdf, apk.
 * A name hit is weighted 2× — matching the name usually means that skill.
 */
object SkillRelevanceMatcher {

    /** Max skills in the hint: 3 covers most tasks; more becomes another ignored list. */
    const val MAX_HINT_SKILLS = 3

    /** Score floor: at least two trigger hits (or one name hit) before hinting, to limit false positives. */
    const val MIN_SCORE = 2

    /** Max description length in the hint so related skills cannot bloat this turn. */
    private const val HINT_DESC_CHARS = 120

    private val LATIN_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9_.+-]{2,}")

    /** CJK Unified Ideographs (BMP) + Extension A. */
    private fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF

    /**
     * Build hint text; returns null on no hits / no skills / a `/skill-name` slash command
     * (slash already injects the skill body this turn, so no loadSkill reminder).
     */
    fun buildHint(skills: List<Skill>, userText: String): String? {
        if (skills.isEmpty() || userText.isBlank()) return null
        if (userText.trimStart().startsWith("/")) return null

        val userTerms = extractTerms(userText)
        if (userTerms.isEmpty()) return null

        val scored = skills.mapNotNull { skill ->
            val score = score(skill, userTerms)
            if (score >= MIN_SCORE) skill to score else null
        }
        if (scored.isEmpty()) return null

        val lines = scored
            .sortedByDescending { it.second }
            .take(MAX_HINT_SKILLS)
            .joinToString("\n") { (skill, _) -> "- ${skill.name}: ${truncate(skill.description)}" }

        return "[System reminder] These skills may relate to this turn: if one fits, you must loadSkill its body and follow it this turn before acting; if none apply, ignore this block.\n$lines"
    }

    /** CJK bigrams plus Latin/digit tokens of length ≥ 3. */
    private fun extractTerms(text: String): Set<String> {
        val terms = LinkedHashSet<String>()
        LATIN_TOKEN.findAll(text).forEach { terms.add(it.value.lowercase()) }
        var i = 0
        while (i < text.length - 1) {
            if (isCjk(text[i]) && isCjk(text[i + 1])) {
                terms.add(text.substring(i, i + 2))
                i += 1 // sliding window so adjacent bigrams both count
            } else {
                i += 1
            }
        }
        return terms
    }

    /** Name hit ×2 + description hit ×1. */
    private fun score(skill: Skill, userTerms: Set<String>): Int {
        var s = 0
        for (token in nameTerms(skill.name)) {
            if (token in userTerms) s += 2
        }
        for (token in extractTerms(skill.description)) {
            if (token in userTerms) s += 1
        }
        return s
    }

    /** Name tokens: Latin/digit words (≥3 chars) plus CJK bigrams, aligned with user-side extraction. */
    private fun nameTerms(name: String): Set<String> {
        val terms = LinkedHashSet<String>()
        LATIN_TOKEN.findAll(name).forEach { terms.add(it.value.lowercase()) }
        var i = 0
        while (i < name.length - 1) {
            if (isCjk(name[i]) && isCjk(name[i + 1])) {
                terms.add(name.substring(i, i + 2))
                i += 1
            } else {
                i += 1
            }
        }
        return terms
    }

    private fun truncate(description: String): String {
        val d = description.trim().ifEmpty { "(no description)" }
        return if (d.length <= HINT_DESC_CHARS) d else d.take(HINT_DESC_CHARS) + "…"
    }
}
