package com.xencode.feature.agent.domain.subagent

import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.model.AgentMode
import com.xencode.feature.agent.domain.model.ReasoningEffort
import com.xencode.feature.workspace.domain.FileAccessProvider
import org.yaml.snakeyaml.Yaml

/**
 * Parses a single sub-agent definition file (`agents/<name>.md`): YAML frontmatter for config, body as the agent prompt.
 * Shares the frontmatter conventions of [com.xencode.feature.agent.domain.skill.SkillParser].
 */
object AgentDefinitionParser {
    private const val TAG = "AgentDefinitionParser"
    private const val MAX_DESC_CHARS = 500

    /** An empty body is an invalid definition (an agent must have a prompt), returning null. Definition files are read via [provider] with container paths. */
    fun parse(provider: FileAccessProvider, filePath: String): AgentDefinition? {
        val text = runCatching { provider.readFile(filePath) }.getOrElse {
            FileLogger.w(TAG, "Failed to read sub-agent definition: $filePath", it)
            return null
        }
        return parseText(text, filePath)
    }

    /**
     * Parses a definition from raw Markdown text (no disk involved), for unified parsing after a
     * [com.xencode.feature.workspace.domain.FileAccessProvider.readFiles] batch read — on a real device's ROOT channel, per-file [parse] costs one `su` subprocess per definition file.
     */
    fun parseText(text: String, filePath: String): AgentDefinition? {
        val (frontmatter, body) = splitAndParseFrontmatter(text)
        val prompt = body.trim()
        if (prompt.isEmpty()) return null

        val fileName = filePath.substringAfterLast('/')
        val name = frontmatter["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: fileName.substringBeforeLast('.')
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)

        return AgentDefinition(
            name = name,
            description = description,
            providerId = frontmatter["provider"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
            model = frontmatter["model"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
            reasoningEffort = frontmatter["reasoningEffort"]?.toString()?.trim()?.lowercase()
                ?.takeIf { it in VALID_EFFORTS },
            mode = parseMode(frontmatter["mode"]),
            allowedTools = stringList(frontmatter["tools"]),
            disallowedTools = stringList(frontmatter["disallowedTools"]),
            inject = parseInject(frontmatter["inject"]),
            prompt = prompt,
            filePath = filePath
        )
    }

    /** Accepts both a YAML list and a comma-separated string. */
    internal fun stringList(raw: Any?): List<String> = when (raw) {
        null -> emptyList()
        is List<*> -> raw.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        is String -> raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    /** Mode parsing: ignores case and hyphen/underscore differences; invalid values fall back to null (inherit from the parent session). */
    internal fun parseMode(raw: Any?): AgentMode? {
        val token = raw?.toString()?.trim()?.uppercase()
            ?.replace("-", "")?.replace("_", "")
            ?.takeIf { it.isNotEmpty() } ?: return null
        return AgentMode.entries.firstOrNull { it.name == token }
    }

    /** Falls back to the default injection set when omitted or all values are invalid, so one typo cannot drop the entire context. */
    internal fun parseInject(raw: Any?): Set<InjectPart> {
        val tokens = stringList(raw)
        if (tokens.isEmpty()) return AgentDefinition.DEFAULT_INJECT
        if (tokens.size == 1 && tokens.first().equals("none", ignoreCase = true)) return emptySet()
        val parsed = tokens.mapNotNull { InjectPart.fromToken(it) }.toSet()
        return parsed.ifEmpty { AgentDefinition.DEFAULT_INJECT }
    }

    private fun splitAndParseFrontmatter(text: String): Pair<Map<String, Any>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized

        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")

        val map = try {
            Yaml().load<Map<String, Any>>(block) ?: emptyMap()
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to parse sub-agent definition YAML", e)
            emptyMap()
        }
        return map to rest
    }

    private val VALID_EFFORTS = ReasoningEffort.entries.map { it.apiValue }.toSet()

    /**
     * Writes the settings-page form back to definition file text (frontmatter + body), the counterpart of [parse].
     *
     * `name` is always written out and kept consistent with the file name, avoiding "one name in the file name, another in the frontmatter".
     * Tool names are identifiers and go straight into the bracket list; every other text field is quoted, so a colon or # in a description cannot corrupt the YAML.
     */
    fun serialize(
        name: String,
        description: String,
        providerId: String?,
        model: String?,
        reasoningEffort: String?,
        mode: AgentMode? = null,
        allowedTools: List<String>,
        disallowedTools: List<String>,
        inject: Set<InjectPart>,
        prompt: String
    ): String = buildString {
        appendLine("---")
        appendLine("name: ${quote(name)}")
        appendLine("description: ${quote(description)}")
        providerId?.takeIf { it.isNotBlank() }?.let { appendLine("provider: ${quote(it)}") }
        model?.takeIf { it.isNotBlank() }?.let { appendLine("model: ${quote(it)}") }
        reasoningEffort?.takeIf { it in VALID_EFFORTS }?.let { appendLine("reasoningEffort: $it") }
        mode?.let { appendLine("mode: ${it.name.lowercase()}") }
        if (allowedTools.isNotEmpty()) appendLine("tools: [${allowedTools.joinToString(", ")}]")
        if (disallowedTools.isNotEmpty()) appendLine("disallowedTools: [${disallowedTools.joinToString(", ")}]")
        // An empty set must be written explicitly as [none]: omitting it parses back as the default injection set, exactly opposite to the user's "inject nothing" choice.
        val injectTokens = if (inject.isEmpty()) {
            "none"
        } else {
            InjectPart.entries.filter { it in inject }.joinToString(", ") { it.token }
        }
        appendLine("inject: [$injectTokens]")
        appendLine("---")
        appendLine(prompt.trim())
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
