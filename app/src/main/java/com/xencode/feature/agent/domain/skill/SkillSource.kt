package com.xencode.feature.agent.domain.skill

/**
 * Abstraction for skill data sources, supporting multiple load channels (local directories, remote URLs, etc.).
 */
interface SkillSource {
    /** Scans and returns all currently valid Skills under this source. */
    fun listSkills(): List<Skill>

    /** Reads a skill's full instruction body; null when missing or unparseable. */
    fun loadInstructions(name: String): String?
}
