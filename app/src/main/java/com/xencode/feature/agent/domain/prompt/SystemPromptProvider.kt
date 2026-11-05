package com.xencode.feature.agent.domain.prompt

import android.content.Context
import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.container.ContainerInstaller
import com.xencode.feature.agent.domain.memory.MemoryRepository
import com.xencode.feature.agent.domain.memory.MemoryScope
import com.xencode.feature.agent.domain.model.AgentContext
import com.xencode.feature.agent.domain.skill.Skill
import com.xencode.feature.agent.domain.skill.SkillListFormatter
import com.xencode.feature.agent.domain.skill.SkillRelevanceMatcher
import com.xencode.feature.agent.domain.skill.SkillRepository
import com.xencode.feature.agent.domain.subagent.AgentDefinition
import com.xencode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.xencode.feature.agent.domain.subagent.InjectPart
import com.xencode.feature.settings.data.repository.ExecutionMode
import com.xencode.feature.settings.data.repository.ExecutionModeHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles the system prompt module by module: the stable baseline goes first (to benefit from the KV cache); only the date changes infrequently.
 * Most Sources maintain a content cache to avoid repeated reads and formatting; the static baseline fragments are the exception — read from disk every time so edits take effect immediately.
 *
 * Fragments fall into two kinds:
 * - Static baseline: top-level `<NN>-<name>.md` under `prompts/`, overridable or extendable by number identity via `prompts.custom/`;
 * - On-demand leaves: un-numbered fragments under `prompts/agent/` (mode reminders, subagent baseline, compaction/title prompts), overridden by exact same name.
 *
 * When `prompts.custom/` contains [PromptFragmentResolver.DISABLE_BUILTIN_FILE], the main-agent prompt consists only of custom numbered fragments,
 * injecting no built-in source at all; `{{AICODE_*}}` variables then pull dynamic content back on demand.
 */
@Singleton
class SystemPromptProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val skillRepository: SkillRepository,
    private val memoryRepository: MemoryRepository,
    private val promptFragmentCatalog: PromptFragmentCatalog,
    private val containerInstaller: ContainerInstaller,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val executionModeHolder: ExecutionModeHolder
) {
    // Abstract standalone Source
    interface PromptSource {
        fun build(ctx: AgentContext): String?
    }

    private inner class StaticRuleSource : PromptSource {
        // Re-read from disk every time: unedited content yields identical strings, so the KV cache still hits; edits take effect immediately without a restart.
        // Runtime content like skills/memories is carried by the trailing 99-runtime-context.md fragment via {{AICODE_*}} variables.
        override fun build(ctx: AgentContext): String = promptFragmentCatalog.renderStatic(ctx.projectRoot)
    }

    private inner class ActiveSkillsSource : PromptSource {
        // Log only once when the list is degraded by the budget: build() runs on every LLM call, and logging each time would flood the log.
        @Volatile private var degradationLogged = false

        // Session-level cache: scan the disk once per (sessionId, projectRoot), keeping the system prompt stable to hit the KV cache;
        // a new session / workspace switch / app restart invalidates it naturally. A failed scan returns an empty list without caching, retrying next round.
        private val cachedListsByKey = ConcurrentHashMap<SourceCacheKey, List<Skill>>()

        /** Snapshot of the enabled skill list: shared by the system prompt list and the per-turn relevant-skill hint to avoid repeated disk scans. */
        fun skills(ctx: AgentContext): List<Skill> {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            cachedListsByKey[key]?.let { return it }
            val skills = try {
                skillRepository.listSkills()
            } catch (e: Exception) {
                FileLogger.w(TAG, "Failed to scan skills: ${e.message}", e)
                return emptyList()
            }
            cachedListsByKey[key] = skills
            trimIfNeeded()
            return skills
        }

        override fun build(ctx: AgentContext): String? {
            val skills = skills(ctx)
            if (skills.isEmpty()) return null
            val content = SkillListFormatter.format(skills)
            if (content.isEmpty()) return null
            logDegradationOnce(skills, content)
            return content
        }

        private fun trimIfNeeded() {
            if (cachedListsByKey.size > SOURCE_CACHE_LIMIT) cachedListsByKey.clear()
        }

        private fun logDegradationOnce(skills: List<Skill>, formatted: String) {
            if (degradationLogged) return
            // If the line count did not drop (every skill listed with its description), no degradation was triggered; don't spam the log.
            if (formatted.lines().size >= skills.size) return
            degradationLogged = true
            val fullChars = skills.sumOf { it.name.length + it.description.length + 4 }
            FileLogger.i(
                TAG,
                "Skill list degraded by budget: ${skills.size} skills, full list about $fullChars chars, " +
                    "actually injected ${formatted.length} chars (budget ${SkillListFormatter.DEFAULT_CHAR_BUDGET})"
            )
        }
    }

    /** Minimal subagent-only baseline: keeps only tool usage, path conventions, and safety boundaries; excludes main-agent-only rules such as mode switching and closing summaries. */
    private inner class SubAgentBaseSource : PromptSource {
        @Volatile private var cached: String? = null

        override fun build(ctx: AgentContext): String =
            cached ?: resolvePrompt(SUBAGENT_BASE_FILE)
                .replace(LEADING_COMMENT, "")
                .trim()
                .also { cached = it }
    }

    /**
     * List of available subagents (injected into the main agent only): lets the AI know which custom agents it can dispatch.
     * Session-level cache, so per-turn disk scans don't jitter the system prompt and break the KV cache.
     */
    private inner class SubAgentListSource : PromptSource {
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, String>()

        override fun build(ctx: AgentContext): String? {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            val cached = cachedByKey[key]
            if (cached != null) return cached.ifEmpty { null }
            val entries = try {
                agentDefinitionRepository.listEnabled()
            } catch (e: Exception) {
                FileLogger.w(TAG, "Failed to scan subagent definitions: ${e.message}", e)
                return null
            }
            if (entries.isEmpty()) {
                cachedByKey[key] = ""
                return null
            }

            val list = entries.joinToString("\n") { entry ->
                "- ${entry.definition.name}: ${entry.definition.description.ifBlank { "(no description)" }}"
            }
            val content = list
            cachedByKey[key] = content
            trimIfNeeded()
            return content
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    private inner class ProjectRuleSource : PromptSource {
        @Volatile private var cached: String? = null
        private var lastModified: Long = 0
        private var lastProjectRoot: String = ""

        override fun build(ctx: AgentContext): String? {
            if (ctx.projectRoot.isBlank()) return null
            val agentsFile = File(ctx.projectRoot, AGENTS_FILE)
            val claudeFile = File(ctx.projectRoot, CLAUDE_FILE)
            val file = when {
                agentsFile.isFile && agentsFile.canRead() -> agentsFile to AGENTS_FILE
                claudeFile.isFile && claudeFile.canRead() -> claudeFile to CLAUDE_FILE
                else -> return null
            }
            
            val currentMod = file.first.lastModified()
            // If the file is unmodified and the path is unchanged, return the cached baseline directly to avoid repeated reads and formatting
            if (ctx.projectRoot == lastProjectRoot && currentMod == lastModified && cached != null) {
                return cached
            }
            
            val text = try { file.first.readText() } catch (e: Exception) { return null }
            if (text.isBlank()) return null
            
            cached = if (text.length > MAX_AGENTS_CHARS) {
                text.take(MAX_AGENTS_CHARS) + "\n… (${file.second} too long, truncated)"
            } else {
                text
            }.trim()
            lastModified = currentMod
            lastProjectRoot = ctx.projectRoot
            return cached
        }
    }

    private inner class WorkspaceSource : PromptSource {
        override fun build(ctx: AgentContext): String =
            if (ctx.projectRoot.isNotBlank()) "~/workspace" else "(no workspace selected)"
    }

    private inner class EnvironmentSource : PromptSource {
        override fun build(ctx: AgentContext): String =
            when (executionModeHolder.currentMode()) {
                ExecutionMode.LOCAL_PROOT -> "local container (PRoot)"
                ExecutionMode.REMOTE_SSH -> "remote SSH server"
                ExecutionMode.DEVICE_ROOT ->
                    "device ROOT (commands run as root via su on this device, not sandboxed; system changes are real and may be irreversible — be careful)"
            }
    }

    /** Memory list split into global/project pure lists, for the `{{AICODE_MEMORY_GLOBAL}}` / `{{AICODE_MEMORY_PROJECT}}` variables. */
    private data class MemoryLists(val global: String?, val project: String?)

    private inner class MemoryListSource {
        // Session-level cache: read the disk once per (sessionId, projectRoot), keeping the system prompt stable to hit the KV cache;
        // a new session / workspace switch / app restart invalidates it naturally. Empty groups are represented by null fields.
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, MemoryLists>()

        fun build(ctx: AgentContext): MemoryLists {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            cachedByKey[key]?.let { return it }
            val memories = try { memoryRepository.listMemories(ctx.projectRoot) } catch (e: Exception) {
                return MemoryLists(null, null)
            }
            val global = memories.filter { it.scope == MemoryScope.GLOBAL }
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "none" } }" }
            val project = memories.filter { it.scope == MemoryScope.PROJECT }
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "none" } }" }
            val result = MemoryLists(global, project)
            cachedByKey[key] = result
            trimIfNeeded()
            return result
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    /** Session-level cache key: one session and one workspace share a single snapshot, avoiding per-turn rescans that would change the system prompt. */
    private data class SourceCacheKey(val sessionId: String?, val projectRoot: String)

    private val staticRuleSource = StaticRuleSource()
    private val subAgentBaseSource = SubAgentBaseSource()
    private val subAgentListSource = SubAgentListSource()
    private val memoryListSource = MemoryListSource()

    private val activeSkillsSource = ActiveSkillsSource()
    private val projectRuleSource = ProjectRuleSource()
    private val workspaceSource = WorkspaceSource()
    private val environmentSource = EnvironmentSource()

    private val customDir: File
        get() = File(containerInstaller.xencodeDir, "prompts.custom")

    /** Top-level numbered fragments of the custom directory (number identity → file), scanned once per process (refreshed only by restarting the app). */
    private val customFragmentsByNumber: Map<Int, File> by lazy {
        PromptFragmentResolver.numberedFragments(customDir).toMap()
    }

    fun build(agentContext: AgentContext): String {
        agentContext.agentDefinition?.let { return buildForSubAgent(it, agentContext) }

        if (PromptFragmentResolver.isBuiltinDisabled(customDir)) {
            return buildCustomOnly(agentContext)
        }

        // 1. Take a baseline snapshot from each Source.
        val rawStatic = staticRuleSource.build(agentContext)
        val skillsContent = activeSkillsSource.build(agentContext)
        val subAgentsContent = subAgentListSource.build(agentContext)
        val memories = memoryListSource.build(agentContext)
        val projectRules = projectRuleSource.build(agentContext)
        val workspaceContent = workspaceSource.build(agentContext)
        val environmentContent = environmentSource.build(agentContext)

        // 2. In-place variable expansion: `{{AICODE_*}}` is replaced with raw data; fragments without variables are kept as-is.
        val staticContent = renderVariables(
            rawStatic,
            skillsContent,
            memories.global,
            memories.project,
            subAgentsContent,
            projectRules,
            workspaceContent,
            environmentContent,
            currentDate()
        )

        // 4. Collapse extra blank lines left by expanding empty lists; a stable baseline first with dynamic content concentrated in the trailing fragment also helps the KV cache.
        return collapseBlankLines(staticContent)
    }

    /**
     * Per-turn "relevant skills" hint (modeled on Claude Code's "Skills relevant to your task:").
     *
     * The skill list in the system prompt is injected once per session; the model fades it out after a few tool turns. Just before this turn's request is sent,
     * re-surfacing the few most relevant skills with their descriptions — based on the latest user input — is a key lever for raising the `loadSkill` call rate.
     * On a hit, returns a short piece of text that the caller appends to the end of this turn's latest user message — nothing is persisted and the system prompt is untouched,
     * so the system prompt and history stay byte-for-byte identical and the provider's prefix cache still hits (KV-cache friendly).
     * No hit / no skills returns null.
     */
    fun buildSkillHint(ctx: AgentContext, latestUserText: String): String? {
        if (latestUserText.isBlank()) return null
        val skills = activeSkillsSource.skills(ctx)
        if (skills.isEmpty()) return null
        return SkillRelevanceMatcher.buildHint(skills, latestUserText)
    }

    /**
     * When [PromptFragmentResolver.DISABLE_BUILTIN_FILE] is in effect: output only the top-level numbered fragments of `prompts.custom/`,
     * injecting no built-in source at all; dynamic content is pulled back on demand only via `{{AICODE_*}}` variables.
     */
    private fun buildCustomOnly(ctx: AgentContext): String {
        val content = promptFragmentCatalog.renderCustomOnly(ctx.projectRoot)
        if (content.isEmpty()) {
            FileLogger.w(
                TAG,
                "${PromptFragmentResolver.DISABLE_BUILTIN_FILE} is enabled, but there are no <two-digit>-<name>.md fragments under $customDir; the system prompt is empty"
            )
            return ""
        }
        val memories = memoryListSource.build(ctx)
        return renderVariables(
            content,
            activeSkillsSource.build(ctx),
            memories.global,
            memories.project,
            subAgentListSource.build(ctx),
            projectRuleSource.build(ctx),
            workspaceSource.build(ctx),
            environmentSource.build(ctx),
            currentDate()
        )
    }

    /**
     * Assembles the prompt from a subagent definition: inject only the fragments listed in [AgentDefinition.inject], then append the agent's own prompt.
     * The available-subagent list is not injected (subagents cannot dispatch nested agents). `{{AICODE_*}}` variables inside the definition body are also expanded.
     */
    private fun buildForSubAgent(
        definition: AgentDefinition,
        agentContext: AgentContext
    ): String = buildString {
        if (InjectPart.MAIN_RULES in definition.inject) {
            append(staticRuleSource.build(agentContext))
            append("\n\n")
        }
        if (InjectPart.BASE in definition.inject) {
            append(subAgentBaseSource.build(agentContext))
            append("\n\n")
        }

        val memories = memoryListSource.build(agentContext)
        append(
            renderVariables(
                definition.prompt,
                activeSkillsSource.build(agentContext),
                memories.global,
                memories.project,
                subAgentListSource.build(agentContext),
                projectRuleSource.build(agentContext),
                workspaceSource.build(agentContext),
                environmentSource.build(agentContext),
                currentDate()
            )
        )

        if (InjectPart.SKILLS in definition.inject) {
            activeSkillsSource.build(agentContext)?.let {
                append("\n\n")
                append(it)
            }
        }
        if (InjectPart.MEMORY in definition.inject) {
            listOfNotNull(memories.global, memories.project).takeIf { it.isNotEmpty() }?.let { groups ->
                append("\n\n")
                append(groups.joinToString("\n\n"))
            }
        }
        if (InjectPart.PROJECT_RULES in definition.inject) {
            projectRuleSource.build(agentContext)?.let {
                append("\n\n")
                append(it)
            }
        }

        append("\n\n")
        append(
            renderVariables(
                resolvePrompt(SUBAGENT_CONTEXT_FILE),
                null,
                null,
                null,
                null,
                null,
                workspaceSource.build(agentContext),
                environmentSource.build(agentContext),
                currentDate(),
                definition.name
            )
        )
    }

    /** Replaces `{{AICODE_*}}` placeholders in a fragment with real content; placeholders that do not occur stay as-is, leaving other placeholders like `{{INSTRUCTION}}` untouched. */
    private fun renderVariables(
        text: String,
        skills: String?,
        memoryGlobal: String?,
        memoryProject: String?,
        subAgents: String?,
        projectRules: String?,
        workspace: String,
        environment: String,
        date: String,
        subAgentName: String = ""
    ): String {
        var out = text
        out = out.replace(SKILLS_VAR, skills.orEmpty())
        out = out.replace(MEMORY_GLOBAL_VAR, memoryGlobal.orEmpty())
        out = out.replace(MEMORY_PROJECT_VAR, memoryProject.orEmpty())
        out = out.replace(SUBAGENTS_VAR, subAgents.orEmpty())
        out = out.replace(PROJECT_RULES_VAR, projectRules.orEmpty())
        out = out.replace(WORKSPACE_VAR, workspace)
        out = out.replace(ENVIRONMENT_VAR, environment)
        out = out.replace(DATE_VAR, date)
        out = out.replace(SUBAGENT_NAME_VAR, subAgentName)
        return out
    }

    private fun currentDate(): String =
        java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    /** Collapses 3+ consecutive newlines into 2: expanding empty lists leaves extra blank lines. */
    private fun collapseBlankLines(text: String): String =
        text.trim().replace(Regex("\\n{3,}"), "\n\n")

    /**
     * Resolves a single prompt fragment by priority:
     * - Names that are top-level `<NN>-*.md`: first look for an override by number identity at the top level of `prompts.custom/` (the trailing name can be changed freely),
     * - Other names (including the `agent/` subdirectory): look for an override by exact same name at `prompts.custom/<name>`;
     * then fall back to `prompts/<name>` (the local default copy), and finally assets (the built-in fallback).
     *
     * The local copy is fully released at startup by [ContainerInstaller.extractPrompts] and updated along with app upgrades.
     */
    fun resolvePrompt(name: String): String {
        PromptFragmentResolver.parseNumber(name)
            ?.let { number -> readFileOrNull(customFragmentsByNumber[number])?.let { return it } }
        readFileOrNull(File(customDir, name))?.let { return it }
        readFileOrNull(File(File(containerInstaller.xencodeDir, "prompts"), name))?.let { return it }
        return context.assets.open("prompts/$name").bufferedReader().use { it.readText() }
    }

    private fun readFileOrNull(file: File?): String? {
        if (file == null || !file.isFile) return null
        return try {
            file.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to read prompt ${file.name}: ${e.message}", e)
            null
        }
    }

    private companion object {
        const val TAG = "SystemPromptProvider"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val SUBAGENT_BASE_FILE = "agent/subagent-base.md"
        const val SUBAGENT_CONTEXT_FILE = "agent/subagent-context.md"
        const val MAX_AGENTS_CHARS = 32_000
        /** Upper bound on session-level cache keys: cleared entirely when exceeded, merely to guard against long-term accumulation; normal session counts are far below this. */
        const val SOURCE_CACHE_LIMIT = 32
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")

        /** Built-in static baseline: number identity → canonical file name, deciding the default concatenation order. */
        // Runtime variables available inside fragments, replaced with real content at render time
        const val SKILLS_VAR = "{{AICODE_SKILLS}}"
        const val MEMORY_GLOBAL_VAR = "{{AICODE_MEMORY_GLOBAL}}"
        const val MEMORY_PROJECT_VAR = "{{AICODE_MEMORY_PROJECT}}"
        const val SUBAGENTS_VAR = "{{AICODE_SUBAGENTS}}"
        const val PROJECT_RULES_VAR = "{{AICODE_PROJECT_RULES}}"
        const val WORKSPACE_VAR = "{{AICODE_WORKSPACE}}"
        const val ENVIRONMENT_VAR = "{{AICODE_ENVIRONMENT}}"
        const val DATE_VAR = "{{AICODE_DATE}}"
        const val SUBAGENT_NAME_VAR = "{{AICODE_SUBAGENT_NAME}}"
    }
}