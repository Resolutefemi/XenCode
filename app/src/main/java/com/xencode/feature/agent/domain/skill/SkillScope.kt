package com.xencode.feature.agent.domain.skill

/** Source scope of a skill: global (shared across projects) or project-level (effective only in the current workspace). */
enum class SkillScope { GLOBAL, PROJECT }

/** One effective skill entry: the skill itself + its source scope, for the UI to label "global/project". */
data class SkillEntry(
    val skill: Skill,
    val scope: SkillScope
)
