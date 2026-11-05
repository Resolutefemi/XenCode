package com.xencode.core.skills

import java.io.File

/**
 * Skill discovery for the PC line — the Claude-Code-style skill contract:
 * a skill is a folder with a `SKILL.md` whose optional frontmatter carries
 * `name` and `description`; the body holds the instructions loaded into the
 * agent context on demand.
 *
 * Search order (first hit wins per name):
 *   1. `<workspace>/.xencode/skills/<name>/SKILL.md`  (project skills)
 *   2. `~/.xencode/skills/<name>/SKILL.md`            (global skills)
 *
 * The Android app reads the same folders through its skill repository, so a
 * skill written on the phone is immediately available to the desktop and CLI.
 */
class SkillStore(private val workspaceRoot: File) {

    data class Skill(
        val name: String,
        val description: String,
        val path: String,
        val body: String
    )

    /** Project skills root, `<workspace>/.xencode/skills`. */
    fun projectSkillsRoot(): File = File(File(workspaceRoot, ".xencode"), "skills")

    /** Global skills root, `~/.xencode/skills`. */
    fun globalSkillsRoot(): File =
        File(File(System.getProperty("user.home") ?: ".", ".xencode"), "skills")

    /** All discoverable skills, project skills first, deduplicated by name. */
    fun list(): List<Skill> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<Skill>()
        for (root in listOf(projectSkillsRoot(), globalSkillsRoot())) {
            val dirs = root.listFiles { f -> f.isDirectory } ?: continue
            for (dir in dirs.sortedBy { it.name }) {
                val skill = parse(dir) ?: continue
                if (skill.name in seen) continue
                seen.add(skill.name)
                out.add(skill)
            }
        }
        return out.sortedBy { it.name }
    }

    /** Loads one skill body by name (frontmatter stripped). */
    fun load(name: String): Skill? {
        if (!name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return null
        return list().firstOrNull { it.name == name }
    }

    /** Parses one skill folder; null when it has no readable SKILL.md. */
    fun parse(dir: File): Skill? {
        val f = File(dir, "SKILL.md")
        if (!f.isFile) return null
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val (meta, body) = frontmatter(text)
        val name = (meta["name"]?.trim()).takeUnless { it.isNullOrBlank() } ?: dir.name
        if (!name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return null
        return Skill(
            name = name,
            description = (meta["description"]?.trim() ?: "").take(160),
            path = f.path,
            body = body.trim()
        )
    }

    companion object {
        /**
         * Splits an optional `---` frontmatter block from the body.
         * Returns (metadata, body); metadata keys are lowercased.
         */
        fun frontmatter(text: String): Pair<Map<String, String>, String> {
            val trimmed = text.trimStart('\uFEFF', '\n', '\r')
            if (!trimmed.startsWith("---")) return emptyMap<String, String>() to text
            val end = trimmed.indexOf("\n---", 3)
            if (end < 0) return emptyMap<String, String>() to text
            val block = trimmed.substring(3, end).trim()
            val rest = trimmed.substring(end + 4).removePrefix("\n")
            val meta = block.lineSequence()
                .mapNotNull { line ->
                    val idx = line.indexOf(':')
                    if (idx <= 0) null
                    else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
                }
                .toMap()
            return meta to rest
        }
    }
}
