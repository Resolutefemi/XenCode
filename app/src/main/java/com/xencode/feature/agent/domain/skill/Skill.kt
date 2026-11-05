package com.xencode.feature.agent.domain.skill

/**
 * Parsed model of a single Skill.
 *
 * @param name Skill name (the unique identifier the LLM invokes)
 * @param description Skill description (when to use it)
 * @param requiredTools Optional list of tools this skill requires
 * @param dirPath Container path of the skill directory (unified local/remote view, e.g. `~/workspace/.xencode/skills/foo`)
 * @param instructions Skill instruction body (content with the frontmatter stripped)
 */
data class Skill(
    val name: String,
    val description: String,
    val requiredTools: List<String> = emptyList(),
    val dirPath: String? = null,
    val instructions: String
)

/** Form snapshot submitted when creating/editing a skill on the settings page. */
data class SkillForm(
    val name: String,
    val description: String,
    val instructions: String,
    /** Hand-written `required_tools` from the skill; not exposed in the editor but written back verbatim so the field survives saving. */
    val requiredTools: List<String> = emptyList()
)

/** Reasons a skill save can fail. */
enum class SkillSaveError {
    /** Name is empty, too long, or contains characters unusable in a directory name. */
    INVALID_NAME,

    /** Instruction body is empty: such a skill offers the AI no guidance once loaded. */
    EMPTY_INSTRUCTIONS,

    /** A skill with the same name already exists in this scope. */
    NAME_CONFLICT,

    /** Disk write failed. */
    IO_FAILED
}
