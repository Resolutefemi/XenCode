package com.xencode.feature.agent.domain.permission

import com.xencode.feature.agent.domain.tool.AgentTool
import com.xencode.feature.agent.domain.tool.ToolCapability
import com.xencode.feature.agent.domain.tool.instabox.InstaboxTool
import com.xencode.feature.settings.data.repository.CommandAuthLevel
import com.xencode.feature.settings.data.repository.ToolSafetySettingsRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool authorization policy engine: before showing a dialog, decides whether a tool call should be
 * auto-allowed / auto-denied / confirmed, and remembers the rule once the user picks "Always allow".
 *
 * Session modes ([com.xencode.feature.agent.domain.model.AgentMode]) and authorization tiers
 * ([CommandAuthLevel]) are decoupled, each with its own job (aligned with Claude Code's Build/Plan/Ask):
 * - ASK: read-only Q&A sandbox; all writes hard-denied; the AI must not switch to plan mode on its own.
 * - PLAN: read-only planning sandbox; all writes hard-denied; exiting requires plan-review panel approval before build.
 * - BUILD: full toolset; the authorization policy **is fully determined by settings** — dispatched by the "command auth tier"
 *   (rule interception / autonomous execution / full permission); there is no mode-level "allow-all" switch anymore
 *   (the old AUTO mode's job is now handled by the "full-permission" tier).
 *
 * Evaluation order (Bash, BUILD mode):
 *   0) Catastrophic rm guard: deleting the root directory / critical system directories / the workspace root etc. → hard DENY (downgradable to a one-time authorization via the elevate parameter);
 *   1) Shizuku high-risk tools: always prompt, never rememberable;
 *   2) Statically analyzable and every segment hits the built-in read-only allowlist (ls/git status etc.) → auto-allow;
 *   3) Dispatch by "command auth tier" ([CommandAuthLevel]):
 *      - Rule interception (default): hitting any enabled high-risk interception rule (see [HighRiskRuleMatcher]) → ASK for manual confirmation;
 *        not statically analyzable → scan the raw text for destructive features (the loose [looksDestructiveLoose]);
 *        no destructive features → ALLOW, some → ASK; otherwise → ALLOW;
 *      - Autonomous execution: always ASK flagged with [EvalResult.aiReviewable]; the workflow layer first routes it to the "AI command reviewer",
 *        approved → allow directly; rejected / uncertain / failed → fall back to a manual dialog;
 *      - Full permission: ALLOW directly, keeping only the step-0 catastrophic guard (elevation allowed; also off when "disable safety interception" is on).
 *
 * Commands no longer support "Always allow" memory: the legacy project/global ALLOW rules took effect ahead of the tier, effectively bypassing high-risk interception.
 * Non-shell tools (file read/write/edit etc.) match as a whole tool (pattern=`*`) and keep the "always allow this tool" memory
 * (these tools have no high-risk rules to match; dropping the memory would prompt on every file write).
 *
 * [SHIZUKU_TOOL] (Shizuku) is treated as high-risk: always ASK and never rememberable; hard-denied in ASK/PLAN modes.
 * The "disable safety interception" switch applies to all modes: when on, the catastrophic rm guard and per-call Shizuku confirmation are both disabled.
 */
@Singleton
class ToolPermissionPolicyEngine @Inject constructor(
    private val rulesRepo: PermissionRulesRepository,
    private val toolSafetySettings: ToolSafetySettingsRepository,
    private val highRiskRulesRepo: HighRiskRulesRepository
) {
    private companion object {
        /** Tools that carry shell commands in their `command` parameter and are matched at the command level by command prefix. */
        val SHELL_TOOLS = setOf("Bash", SHIZUKU_TOOL)

        /** Shizuku tool: operates directly on the host Android system as adb shell; treated as high-risk (always prompts, never rememberable). */
        const val SHIZUKU_TOOL = "Shizuku"

        const val REASON_SHIZUKU =
            "Shizuku runs as adb shell on the host Android system. High-risk; single-use only, cannot be remembered"

        /**
         * Merged terminal-session tool: its `start` action carries a shell command and needs command-level prefix matching;
         * `send`/`read` actions carry no command and match as a whole tool. Hence routing by action in [evaluate].
         */
        const val TERMINAL_TOOL = "terminal"
        const val TERMINAL_SHELL_ACTION = "start"
        val NON_REMEMBERABLE_CAPABILITIES = setOf(
            ToolCapability.MODIFY_AGENT_CONFIG,
            ToolCapability.MODIFY_CONTAINER_ENV
        )

        /**
         * Merged subagent tool: read-only and messaging actions (read/list/send) are auto-allowed without a dialog;
         * write actions (create/stop) go through normal rule evaluation.
         */
        const val TASK_TOOL = "task"
        private val TASK_AUTO_ACTIONS = setOf("read", "list", "send")

        /**
         * Browser tool: navigate/click/fill/select/evaluate/dialog/back/forward/reload/newTab/closeTab/selectTab are writes (blocked in PLAN);
         * getText/getHtml/getBackbone/screenshot/console/wait/listTabs are read-only (allowed in PLAN).
         */
        const val BROWSER_TOOL = "browser"
        private val BROWSER_READ_ONLY_ACTIONS = setOf("getText", "getHtml", "getBackbone", "screenshot", "console", "wait", "listTabs")

        const val LINUX_MACHINE_TOOL = "linux_machine"

        /**
         * Elevation parameter: when a command is rejected by the built-in safety guard (catastrophic rm),
         * retrying the call with this set to true downgrades the hard denial to a one-time user authorization.
         */
        const val ELEVATE_ARG = "elevate"

        /** What `~` expands to inside the container (PRoot runs as root). */
        const val HOME_DIR = "/root"
        const val HOME_TOKEN = "\$HOME"
        const val HOME_BRACED = "\${HOME}"

        const val REASON_RM_ROOT = "Safety: high-risk delete blocked (root directory)"
        const val REASON_RM_RELATIVE = "Safety: high-risk delete blocked (global or relative-path wildcard)"
        const val REASON_RM_HOME = "Safety: high-risk delete blocked (home directory)"
        const val REASON_RM_WORKSPACE = "Safety: high-risk delete blocked (workspace root)"
        const val REASON_RM_TMP = "Safety: high-risk delete blocked (entire system temp directory)"
        const val REASON_RM_SYSTEM_DIR = "Safety: high-risk delete blocked (critical system directory)"

        const val REASON_PLAN_DENY =
            "Currently in PLAN mode. The sandbox blocks writes and system changes. Use only read-only tools to explore; do not modify files or run commands."

        const val REASON_ASK_DENY =
            "Currently in ASK mode. The sandbox blocks writes and system changes. Stay read-only; if changes are needed, ask the user to switch to Build or Plan mode."

        const val REASON_ASK_NO_PLAN_SWITCH =
            "Currently in ASK mode. The AI must not switch to Plan mode on its own. If planning is needed, the user should switch to Plan mode in the UI."

        /** Relative/wildcard delete targets (judged after normalization): deleting the whole current directory or arbitrary content. */
        val RELATIVE_WILDCARD_TARGETS = setOf("*", ".*", ".", "..", "../*", "../.*")

        /** Root-level globs (`/et*`, `/usr*` etc.) may expand to protected system directories; blocked outright. */
        val ROOT_LEVEL_GLOB = Regex("^/[^/*?]*[*?]")

        /** Delete targets that equal the workspace root (judged after normalization); workspace subdirectories are not included. */
        val WORKSPACE_ROOT_TARGETS = setOf("workspace", "workspace/*", "$HOME_DIR/workspace", "$HOME_DIR/workspace/*")

        /** Protected critical system directories: a hit on the dir itself or any subpath under it is blocked (`/tmp` and the workspace subtree are handled separately). */
        val PROTECTED_SYSTEM_DIRS = setOf(
            "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64", "/lost+found",
            "/media", "/mnt", "/opt", "/proc", "/root", "/run", "/sbin", "/srv", "/sys", "/usr", "/var"
        )

        /** Suspected destructive programs (used for conservative interception only when a command cannot be statically judged). */
        val DESTRUCTIVE_PROGRAM = Regex(
            "(^|[\\s;&|()`<>])(rm|dd|shred|truncate|mkfs(?:\\.[a-z0-9]+)?|wipefs|fdisk|sfdisk|parted|mkswap|blkdiscard)([\\s;&|()`<>]|$)"
        )
    }

    enum class Verdict { ALLOW, DENY, ASK }

    /**
     * @param verdict The evaluation outcome.
     * @param rememberablePatterns When [verdict] is ASK, the patterns "Always allow" would remember; empty means not rememberable
     *   (the command cannot be statically judged, so it can only be allowed once).
     * @param aiReviewable true means this ASK came from the "autonomous execution" tier; the workflow layer should first send it to the AI command reviewer,
     *   falling back to a manual dialog only when the review rejects / is uncertain / fails.
     */
    data class EvalResult(
        val verdict: Verdict,
        val rememberablePatterns: List<String>,
        val denyReason: String? = null,
        val rememberDisabledReason: String? = null,
        /** Dialog title override for ASK; null means use the tool's default title. */
        val askTitle: String? = null,
        val aiReviewable: Boolean = false
    )

    suspend fun evaluate(tool: AgentTool?, toolName: String, args: Map<String, JsonElement>, mode: com.xencode.feature.agent.domain.model.AgentMode): EvalResult {
        val capabilities = tool?.effectiveCapabilities(args).orEmpty()
        if (mode == com.xencode.feature.agent.domain.model.AgentMode.PLAN && isDangerousTool(toolName, args, capabilities)) {
            return EvalResult(Verdict.DENY, emptyList(), denyReason = REASON_PLAN_DENY)
        }

        if (mode == com.xencode.feature.agent.domain.model.AgentMode.ASK) {
            // ASK is a purely read-only Q&A mode: writes are hard-denied as in PLAN; the AI is also barred from switching to plan mode on its own —
            // mode switches can only be done by the user in the UI (planMode's MODIFY_SESSION_STATE capability is not in
            // isDangerousTool's dangerous capability set, so it needs separate interception, otherwise it would run after the dialog).
            if (toolName == "planMode") {
                return EvalResult(Verdict.DENY, emptyList(), denyReason = REASON_ASK_NO_PLAN_SWITCH)
            }
            if (isDangerousTool(toolName, args, capabilities)) {
                return EvalResult(Verdict.DENY, emptyList(), denyReason = REASON_ASK_DENY)
            }
        }

        if (capabilities == setOf(ToolCapability.READ_AGENT_CONFIG)) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        // task read-only actions (read/list): auto-allow directly (no dialog).
        if (toolName == TASK_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
            if (action in TASK_AUTO_ACTIONS) return EvalResult(Verdict.ALLOW, emptyList())
        }

        val level = toolSafetySettings.getCommandAuthLevel()
        // "Disable safety interception" applies to all modes (the old AUTO-only switch went away together with AUTO):
        // when on, the catastrophic rm guard and per-call Shizuku confirmation are both disabled, and all commands are allowed.
        val safetyDisabled = toolSafetySettings.isSafetyInterceptionDisabled()
        return if (isShellTool(toolName, args)) {
            // Only the "rule interception" tier needs the high-risk rule set; "full permission" no longer reads the safety switch (see evaluateShell).
            val highRiskRules = if (level == CommandAuthLevel.RULE_BASED) highRiskRulesRepo.getRulesOnce() else emptyList()
            evaluateShell(
                args,
                forceAsk = toolName == SHIZUKU_TOOL,
                level = level,
                highRiskRules = highRiskRules,
                safetyDisabled = safetyDisabled
            )
        } else {
            evaluateGeneric(toolName, capabilities, level)
        }
    }

    private fun isDangerousTool(toolName: String, args: Map<String, JsonElement>, capabilities: Set<ToolCapability>): Boolean {
        val dangerousCapabilities = setOf(
            ToolCapability.WRITE_WORKSPACE,
            ToolCapability.EXECUTE_COMMANDS,
            ToolCapability.NETWORK_WRITE,
            ToolCapability.MODIFY_AGENT_CONFIG,
            ToolCapability.MODIFY_CONTAINER_ENV,
            ToolCapability.EXTERNAL_TOOL
        )
        if (capabilities.any { it in dangerousCapabilities }) return true

        // Read-only exploration tools are always safe in PLAN mode
        val safePlanModeTools = setOf("list", "search")
        if (toolName in safePlanModeTools) return false

        val dangerousTools = setOf(
            "writeFile",
            "editFile",
            "Bash"
        )
        if (toolName in dangerousTools) return true
        if (toolName == TERMINAL_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            return action != "read"
        }
        if (toolName == BROWSER_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()
            return action !in BROWSER_READ_ONLY_ACTIONS
        }
        if (toolName == LINUX_MACHINE_TOOL) {
            val action = InstaboxTool.cleanAction((args["action"] as? JsonPrimitive)?.content)
            return action !in InstaboxTool.READ_ONLY_ACTIONS
        }
        return false
    }

    /** Whether to match by shell command prefix: tools in [SHELL_TOOLS], or the terminal tool's start/send actions. */
    private fun isShellTool(toolName: String, args: Map<String, JsonElement>): Boolean {
        if (toolName in SHELL_TOOLS) return true
        if (toolName == TERMINAL_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            return action == TERMINAL_SHELL_ACTION || action == "send"
        }
        return false
    }

    /** Whether the call carries the elevation parameter (`elevate: true`). */
    private fun isElevationRequested(args: Map<String, JsonElement>): Boolean =
        (args[ELEVATE_ARG] as? JsonPrimitive)?.content?.trim()?.lowercase() == "true"

    /**
     * Destructiveness check (the fallback triage for commands that "cannot be statically analyzed"). Prefer asking too often over letting one slip — but not to the point of
     * labeling every read-only diagnostic command destructive. Only these count:
     * 1) a [DESTRUCTIVE_PROGRAM] hit (rm/dd/mkfs...; the regex boundaries include `()` and backticks,
     *    covering destruction hidden inside command substitution like `$(rm -rf x)`);
     * 2) `find ... -delete`;
     * 3) an overwrite redirect `> target` — where the target is not /dev/null, not an fd duplicate (2>&1),
     *    and not an `>>` append.
     *
     * History: the strict version treated any `>` as destructive; AI diagnostic commands almost always carry `2>/dev/null`,
     * so many commands unrelated to deletion were misjudged as destructive (both the RULE_BASED and AUTO tiers have since switched to this loose version);
     * the strict implementation was deleted, leaving only this function.
     */
    private fun looksDestructiveLoose(command: String): Boolean =
        command.contains("-delete") ||
            DESTRUCTIVE_PROGRAM.containsMatchIn(command) ||
            hasOverwriteRedirect(command)

    /** Whether an overwrite redirect exists (`> file`). `>>` appends, `2>/dev/null`, `2>&1`, and `<<<` do not count. */
    private fun hasOverwriteRedirect(command: String): Boolean {
        var i = 0
        val n = command.length
        while (i < n) {
            if (command[i] == '>') {
                val double = i + 1 < n && command[i + 1] == '>'
                // a > inside a herestring (<<<) does not count as a redirect
                if (i > 0 && command[i - 1] == '<') { i++; continue }
                if (!double) {
                    val rest = command.substring(i + 1).trimStart()
                    // empty target / fd duplication (&1) / discarded output (/dev/null) are all harmless
                    if (rest.isNotEmpty() && !rest.startsWith("&") && !rest.startsWith("/dev/null")) {
                        return true
                    }
                }
                i += if (double) 2 else 1
            } else {
                i++
            }
        }
        return false
    }

    /**
     * Verdict for catastrophic deletes: with the elevation parameter, downgraded to a one-time user authorization (ASK); otherwise hard-denied (DENY)
     * with the reason hinting that an elevated retry is possible. Shared by AUTO and non-AUTO modes. Elevation applies to this call only and is never rememberable.
     */
    private fun elevationOrDeny(catastrophicReason: String, args: Map<String, JsonElement>): EvalResult =
        if (isElevationRequested(args)) {
            EvalResult(
                verdict = Verdict.ASK,
                rememberablePatterns = emptyList(),
                askTitle = "Confirm elevated high-risk operation",
                rememberDisabledReason = "Command hit built-in safety. Elevation is single-use only and cannot be remembered"
            )
        } else {
            EvalResult(
                Verdict.DENY,
                emptyList(),
                denyReason = "$catastrophicReason. If you truly need this, retry with `$ELEVATE_ARG: true` so the system can ask the user to authorize."
            )
        }

    /** Persists an "Always allow" choice as an ALLOW rule (deduplication is up to the repository). */
    suspend fun remember(toolName: String, patterns: List<String>, scope: PermissionScope) {
        patterns.distinct().forEach { pattern ->
            rulesRepo.add(scope, PermissionRule(toolName, pattern, PermissionDecision.ALLOW))
        }
    }

    /**
     * Verdict for non-shell tools (file read/write/edit etc.).
     *
     * Authorization for command tools has been consolidated into the three tiers + high-risk rules; the legacy "project/global" memory rules no longer participate;
     * but "Always allow" memory is kept for non-command tools — these tools have no high-risk rules to match,
     * and without the memory every file write would need a dialog, making them unusable.
     */
    private suspend fun evaluateGeneric(
        toolName: String,
        capabilities: Set<ToolCapability>,
        level: CommandAuthLevel
    ): EvalResult {
        if (capabilities.any { it in NON_REMEMBERABLE_CAPABILITIES }) {
            return EvalResult(
                Verdict.ASK,
                rememberablePatterns = emptyList(),
                rememberDisabledReason = "This tool changes Agent config, the container environment, or calls external dynamic tools. Single-use only to reduce accidental grants"
            )
        }
        // Full permission: command and non-command tools are all allowed.
        if (level == CommandAuthLevel.FULL_ACCESS) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }
        // A remembered whole-tool ALLOW ("always allow this tool") → allow directly.
        val rules = rulesRepo.loadEffectiveForCurrentProject()
        if (rules.any { it.toolName == toolName && it.decision == PermissionDecision.ALLOW && it.pattern == PermissionRule.WHOLE_TOOL }) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }
        return EvalResult(Verdict.ASK, listOf(PermissionRule.WHOLE_TOOL))
    }

    private fun checkCatastrophicRm(segments: List<List<String>>): String? {
        for (seg in segments) {
            val rmInfo = ShellCommandParser.parseRmInfo(seg)
            if (!rmInfo.isRm) continue
            for (rawPath in rmInfo.targetPaths) {
                catastrophicReasonFor(rawPath)?.let { return it }
            }
        }
        return null
    }

    /** Verdict for a single delete target: returns the corresponding reason when it hits a protected scope, otherwise null. */
    private fun catastrophicReasonFor(rawPath: String): String? {
        val raw = rawPath.trim()
        if (raw.isEmpty()) return null
        val path = normalizePath(raw)
        return when {
            path == "/" || path == "/*" -> REASON_RM_ROOT
            path in RELATIVE_WILDCARD_TARGETS -> REASON_RM_RELATIVE
            ROOT_LEVEL_GLOB.matches(path) -> REASON_RM_SYSTEM_DIR
            path == HOME_DIR || path == "$HOME_DIR/*" -> REASON_RM_HOME
            path in WORKSPACE_ROOT_TARGETS -> REASON_RM_WORKSPACE
            // Workspace subdirectories (build artifacts etc.) are normal operations; placed before the system-dir check so they are allowed
            path.startsWith("$HOME_DIR/workspace/") || path.startsWith("workspace/") -> null
            path == "/tmp" || path == "/tmp/*" -> REASON_RM_TMP
            PROTECTED_SYSTEM_DIRS.any { path == it || path.startsWith("$it/") } -> REASON_RM_SYSTEM_DIR
            else -> null
        }
    }

    /**
     * Lexical path normalization: expands a leading `~` / `$HOME` / `${HOME}` to the container home directory, collapses repeated `/`,
     * and resolves `.` and `..`. Pure string handling without file system access, so it cannot cover paths only knowable at runtime, like `$(...)`.
     */
    private fun normalizePath(raw: String): String {
        val expanded = when {
            raw == "~" -> HOME_DIR
            raw.startsWith("~/") -> HOME_DIR + raw.drop(1)
            raw == HOME_TOKEN || raw == HOME_BRACED -> HOME_DIR
            raw.startsWith("$HOME_TOKEN/") -> HOME_DIR + raw.drop(HOME_TOKEN.length)
            raw.startsWith("$HOME_BRACED/") -> HOME_DIR + raw.drop(HOME_BRACED.length)
            else -> raw
        }
        val absolute = expanded.startsWith("/")
        val parts = ArrayDeque<String>()
        for (part in expanded.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeLast() else if (!absolute) parts.addLast("..")
                else -> parts.addLast(part)
            }
        }
        val joined = parts.joinToString("/")
        return when {
            absolute -> "/$joined"
            joined.isEmpty() -> "."
            else -> joined
        }
    }

    private fun evaluateShell(
        args: Map<String, JsonElement>,
        forceAsk: Boolean = false,
        level: CommandAuthLevel,
        highRiskRules: List<HighRiskRule>,
        safetyDisabled: Boolean = false
    ): EvalResult {
        val command = ((args["command"] ?: args["input"]) as? JsonPrimitive)?.content
            ?: return EvalResult(Verdict.ASK, emptyList())

        val analysis = ShellCommandParser.analyze(command)

        // 0) High-risk rm guard: blocks direct deletion of the system root, the workspace root, or critical system directories.
        //    With the elevation parameter, downgraded to a one-time user authorization. Shared by all tiers;
        //    skipped when "disable safety interception" is on (formerly an AUTO-only switch, now effective for all modes).
        if (!safetyDisabled) {
            val catastrophicReason = checkCatastrophicRm(analysis.segments)
            if (catastrophicReason != null) {
                return elevationOrDeny(catastrophicReason, args)
            }
        }

        // 1) Shizuku high-risk tool: regardless of the built-in allowlist, always prompt and never remember (except when safety interception is off).
        if (forceAsk && !safetyDisabled) {
            return EvalResult(Verdict.ASK, emptyList(), rememberDisabledReason = REASON_SHIZUKU)
        }

        // 2) Statically analyzable and every segment hits the built-in read-only allowlist (ls/git status etc.) → auto-allow.
        //    Analyzability is required: in command-substitution attacks like `cat $(rm -rf /)` the segment head is still cat, so it must not be allowed on the allowlist alone.
        if (analysis.analyzable && analysis.segments.isNotEmpty() &&
            analysis.segments.all { BuiltInSafeCommands.isSafe(it) }
        ) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        // 3) Dispatch by tier.
        //    Note: the legacy "project/global" memory rules (ALLOW/DENY) are fully retired — they took effect ahead of the tier,
        //    effectively bypassing the high-risk interception rules; authorization is now decided solely by the three tiers + high-risk rules.
        return when (level) {
            // Full permission: beyond the catastrophic guard (step 0's checkCatastrophicRm), **allow everything outright**.
            // There used to be another fallback here: "not statically analyzable and looks destructive → block once", but it relied on
            // the strict "any `>` counts as destructive" rule: a single `>` anywhere in the command triggered it, even
            // output-discarding forms like `2>/dev/null`. AI diagnostic commands almost always carry
            // `$()`/`2>/dev/null`, so plenty of commands unrelated to deletion were hard-denied
            // (elevationOrDeny shows no dialog and fails outright), contradicting the "full permission" semantics.
            // The user chose this tier deliberately, so execute commands literally.
            CommandAuthLevel.FULL_ACCESS -> EvalResult(Verdict.ALLOW, emptyList())

            // Rule interception: high-risk rule hit → manual confirmation; not statically analyzable → destructive-feature scan; the rest auto-allowed.
            CommandAuthLevel.RULE_BASED -> {
                HighRiskRuleMatcher.matchAny(highRiskRules, command)?.let { hit ->
                    return askWithRememberable(
                        analysis,
                        askTitle = "High-risk rule: ${hit.description.ifBlank { hit.pattern }}"
                    )
                }
                if (!analysis.analyzable) {
                    // Not statically analyzable (command substitution/subshell/loops etc.). This used to always prompt — AI diagnostic
                    // commands are full of $() and backticks; in practice every one hit the fallback branch while the high-risk rules never
                    // fired, making the authorization dialogs feel like spam. Instead, scan the **raw text** for destructive features:
                    // the DESTRUCTIVE_PROGRAM regex boundaries include `()` and backticks, covering the inside of $();
                    // overwrite redirects (> file) are judged separately (2>/dev/null, >>, 2>&1 are harmless).
                    // No destructive features → allow; some → manual confirmation.
                    if (looksDestructiveLoose(command)) {
                        return askWithRememberable(
                            analysis,
                            askTitle = "Command uses complex construction (substitution/subshell) and looks destructive; needs confirmation"
                        )
                    }
                    return EvalResult(Verdict.ALLOW, emptyList())
                }
                EvalResult(Verdict.ALLOW, emptyList())
            }

            // Autonomous execution: uniformly hand off to the AI command reviewer (handled in the workflow layer); fall back to a human only if the review fails.
            CommandAuthLevel.AUTONOMOUS -> askWithRememberable(analysis, aiReviewable = true)
        }
    }

    /**
     * Builds a command-type ASK verdict. "Always allow" memory for commands is retired — it would land in project/global rules,
     * taking effect ahead of the tier and the high-risk interception rules, effectively bypassing the authorization tier; commands only support single-use approval.
     */
    private fun askWithRememberable(
        analysis: ShellCommandParser.Analysis,
        askTitle: String? = null,
        aiReviewable: Boolean = false
    ): EvalResult {
        val hasRmUnrememberable = analysis.segments.any { seg ->
            val rmInfo = ShellCommandParser.parseRmInfo(seg)
            rmInfo.isRm && (rmInfo.targetPaths.isEmpty() || rmInfo.isRecursive || rmInfo.isWildcard)
        }
        val reason = if (hasRmUnrememberable) {
            "High-risk delete (recursive/wildcard/forced bulk). Cannot be remembered; single-use only"
        } else {
            "Command auth is managed by auth level + high-risk rules. Single-use only"
        }
        return EvalResult(
            verdict = Verdict.ASK,
            rememberablePatterns = emptyList(),
            rememberDisabledReason = reason,
            askTitle = askTitle,
            aiReviewable = aiReviewable
        )
    }
}
