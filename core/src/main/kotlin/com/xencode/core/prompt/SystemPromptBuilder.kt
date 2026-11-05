package com.xencode.core.prompt

import java.io.File

/**
 * Builds the agent system prompt for the PC line: base identity + tool contract
 * + workspace rules (XENCODE.md / CLAUDE.md / AGENTS.md, project then global),
 * mirroring the memory-convention of the established coding agents.
 */
object SystemPromptBuilder {

    private const val MAX_RULES_CHARS = 4000

    /** Project rules file candidates, most specific first. */
    val PROJECT_RULE_FILES = listOf("XENCODE.md", "CLAUDE.md", "AGENTS.md")

    fun projectRulesPath(workspaceRoot: String): String? =
        PROJECT_RULE_FILES.firstNotNullOfOrNull { name ->
            val f = File(workspaceRoot, name)
            if (f.isFile) f.path else null
        }

    fun globalRulesPath(): String? {
        val home = System.getProperty("user.home") ?: return null
        val f = File(home + "/.xencode", "XENCODE.md")
        return if (f.isFile) f.path else null
    }

    fun build(
        workspaceRoot: String,
        toolNames: List<String>,
        extraRules: String? = null,
        now: Long = System.currentTimeMillis()
    ): String = buildString {
        appendLine("You are XenCode, an AI coding agent running on the user's machine (CLI or desktop app).")
        appendLine()
        appendLine("## Environment")
        appendLine("- Workspace root: $workspaceRoot. Tool paths are workspace-relative and cannot escape it.")
        appendLine("- OS: ${System.getProperty("os.name")} (${System.getProperty("os.arch")}); shell commands run through sh (cmd on Windows).")
        appendLine("- Date: ${java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(now))}")
        appendLine()
        appendLine("## Tools")
        if (toolNames.isEmpty()) {
            appendLine("(no tools available)")
        } else {
            appendLine(toolNames.joinToString(", "))
            appendLine("MCP tools are prefixed `mcp_<server>_<tool>`.")
        }
        appendLine()
        appendLine("## Rules")
        appendLine("- Use the tools for anything that touches files or commands; never pretend to have run them.")
        appendLine("- Prefer surgical edits (edit_file) over whole-file rewrites; read before you edit.")
        appendLine("- Track multi-step work in the shared TODO list (todo_write), one item per step.")
        appendLine("- Run builds/tests after changes (run_command); report real exit codes.")
        appendLine("- Show what you are about to change before destructive edits.")
        appendLine("- Reply concisely, no emoji. If a request is unclear, ask one short clarifying question.")
        extraRules?.let {
            appendLine(it.trimEnd())
        }

        // Project rules: XENCODE.md -> CLAUDE.md -> AGENTS.md.
        val projectRules = projectRulesPath(workspaceRoot)?.let { path ->
            File(path).readText(Charsets.UTF_8)
        }
        if (projectRules != null) {
            appendLine()
            appendLine("## Project rules (${File(projectRulesPath(workspaceRoot)!!).name})")
            appendLine(clip(projectRules.trim(), MAX_RULES_CHARS))
        }

        // Global rules: ~/.xencode/XENCODE.md.
        val globalRules = globalRulesPath()?.let { File(it).readText(Charsets.UTF_8) }
        if (globalRules != null) {
            appendLine()
            appendLine("## Global rules (~/.xencode/XENCODE.md)")
            appendLine(clip(globalRules.trim(), MAX_RULES_CHARS))
        }
    }

    private fun clip(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max) + "\n...(rules clipped at $max chars)"
}
