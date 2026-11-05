package com.xencode.feature.agent.domain.skill

import com.xencode.core.util.FileLogger
import com.xencode.feature.workspace.domain.FileAccessProvider
import org.yaml.snakeyaml.Yaml

object SkillParser {
    private const val TAG = "SkillParser"

    /**
     * Cap for a single description. Benchmarked against Claude Code's 250 chars: in the system prompt list a description only serves as the
     * "when to use me" index, and writing it long just dilutes the list (with 118 skills, descriptions alone could run ten thousand chars); full execution steps belong
     * in the body that appears only after `loadSkill`. A longer description typed on the settings page is not an error — it is just truncated on read.
     */
    private const val MAX_DESC_CHARS = 250
    private const val SKILL_FILE = "SKILL.md"
    private const val CLAUDE_FILE = "CLAUDE.md"

    /**
     * Parses one skill directory; without SKILL.md / CLAUDE.md or a name it is invalid and null is returned.
     * The directory is read via [provider] with container paths, unified for local/remote.
     */
    fun parse(provider: FileAccessProvider, dirPath: String): Skill? {
        val dir = dirPath.trimEnd('/')
        // Prefer SKILL.md, then CLAUDE.md (for skills that only ship CLAUDE.md); name matching is case-insensitive
        val fileName = runCatching {
            provider.listFiles(dir).map { it.name }
        }.getOrElse {
            FileLogger.w(TAG, "Failed to list skill directory: $dir", it)
            return null
        }.let { names ->
            names.firstOrNull { it.equals(SKILL_FILE, ignoreCase = true) }
                ?: names.firstOrNull { it.equals(CLAUDE_FILE, ignoreCase = true) }
        } ?: return null

        val text = runCatching { provider.readFile("$dir/$fileName") }.getOrElse {
            FileLogger.w(TAG, "Failed to read Skill file: $dir/$fileName", it)
            return null
        }

        return parseText(text, dir.substringAfterLast('/').ifBlank { dir }).copy(dirPath = dir)
    }

    /**
     * Parses a skill from raw Markdown text (no disk involved), used by file/archive import.
     * [fallbackName] is the fallback when the frontmatter lacks a name (usually the source file or directory name).
     */
    fun parseText(text: String, fallbackName: String): Skill {
        val (frontmatter, body) = splitAndParseFrontmatter(text)

        // name comes from the frontmatter first, falling back to the fallback name
        val name = frontmatter["name"]?.toString()?.takeIf { it.isNotBlank() } ?: fallbackName
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)

        val requiredTools = try {
            val toolsRaw = frontmatter["required_tools"]
            if (toolsRaw is List<*>) toolsRaw.filterIsInstance<String>() else emptyList()
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to parse required_tools", e)
            emptyList()
        }

        return Skill(
            name = name,
            description = description,
            requiredTools = requiredTools,
            dirPath = null,
            instructions = body.trim()
        )
    }

    /**
     * Splits and parses YAML frontmatter using SnakeYAML.
     * @return (frontmatter key-value pairs, body)
     */
    private fun splitAndParseFrontmatter(text: String): Pair<Map<String, Any>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized

        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")
        
        val map = try {
            val yaml = Yaml()
            val loaded = yaml.load<Map<String, Any>>(block)
            loaded ?: emptyMap()
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to parse YAML", e)
            emptyMap()
        }
        
        return map to rest
    }

    /**
     * Writes the settings-page form back to `SKILL.md` text (frontmatter + body), the counterpart of [parse].
     *
     * `name` is always written out: the skill name comes from the frontmatter first, so renaming only changes it here without touching the directory name (scripts in the skill
     * directory are often referenced by their original path in the body, and renaming along would break those references).
     * Tool names are identifiers and go straight into the bracket list; every other text field is quoted, so a colon or # in a description cannot corrupt the YAML.
     */
    fun serialize(
        name: String,
        description: String,
        requiredTools: List<String>,
        instructions: String
    ): String = buildString {
        appendLine("---")
        appendLine("name: ${quote(name)}")
        appendLine("description: ${quote(description)}")
        if (requiredTools.isNotEmpty()) appendLine("required_tools: [${requiredTools.joinToString(", ")}]")
        appendLine("---")
        appendLine(instructions.trim())
    }

    /** Frontmatter string value: double-quoted, escapes backslashes and quotes, newlines squeezed to spaces to stay single-line. */
    private fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace(Regex("\\s*\\n\\s*"), " ")
            .trim()
        return "\"$escaped\""
    }
}
