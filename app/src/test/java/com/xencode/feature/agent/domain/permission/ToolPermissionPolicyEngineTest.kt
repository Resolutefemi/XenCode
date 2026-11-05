package com.xencode.feature.agent.domain.permission

import com.xencode.feature.agent.domain.model.AgentMode
import com.xencode.feature.agent.domain.tool.AgentTool
import com.xencode.feature.agent.domain.tool.ToolCapability
import com.xencode.feature.settings.data.repository.CommandAuthLevel
import com.xencode.feature.settings.data.repository.ToolSafetySettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch coverage for the tool authorization policy engine. Rule loading is injected via mocks; the focus is the verdict logic of [ToolPermissionPolicyEngine.evaluate]:
 * PLAN/ASK mode special cases, the full-access tier, DENY precedence, allowlist/remembered rules, rm high-risk deletion guards, and non-rememberable downgrades.
 * Parsing details of the underlying [ShellCommandParser] / [BuiltInSafeCommands] are covered by their own tests and not repeated here.
 */
class ToolPermissionPolicyEngineTest {

    /**
     * Builds the policy engine: authorization rules and tier are injected via mocks; high-risk rules default to the real preset set (close to production behavior),
     * the tier defaults to [CommandAuthLevel.RULE_BASED] (matching the repository default), overridable per case.
     */
    private fun engine(
        vararg rules: PermissionRule,
        safetyDisabled: Boolean = false,
        level: CommandAuthLevel = CommandAuthLevel.RULE_BASED,
        highRiskRules: List<HighRiskRule> = HighRiskRulePresets.RULES
    ): ToolPermissionPolicyEngine {
        val repo = mockk<PermissionRulesRepository>(relaxed = true)
        coEvery { repo.loadEffectiveForCurrentProject() } returns rules.toList()
        val safety = mockk<ToolSafetySettingsRepository>(relaxed = true)
        coEvery { safety.isSafetyInterceptionDisabled() } returns safetyDisabled
        coEvery { safety.getCommandAuthLevel() } returns level
        val highRiskRepo = mockk<HighRiskRulesRepository>(relaxed = true)
        coEvery { highRiskRepo.getRulesOnce() } returns highRiskRules
        return ToolPermissionPolicyEngine(repo, safety, highRiskRepo)
    }

    private fun tool(vararg caps: ToolCapability): AgentTool {
        val t = mockk<AgentTool>(relaxed = true)
        every { t.capabilities } returns caps.toSet()
        every { t.effectiveCapabilities(any()) } returns caps.toSet()
        return t
    }

    private fun bash(command: String) = mapOf("command" to JsonPrimitive(command))

    private fun terminal(action: String) = mapOf("action" to JsonPrimitive(action))

    // ── PLAN mode: write/execute tools are always rejected ─────────────────────────────

    @Test
    fun planMode_deniesWorkspaceWriteTool() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertNotNull(r.denyReason)
    }

    @Test
    fun planMode_deniesBash() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun planMode_deniesTerminalStart() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "terminal", terminal("start"), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun planMode_allowsReadOnlyExplorerTool() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_WORKSPACE), "list", emptyMap(), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    @Test
    fun planMode_allowsTerminalRead() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_WORKSPACE), "terminal", terminal("read"), AgentMode.PLAN)
        // With no rules at all, whole-tool ASK (rememberable) rather than DENY
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── Full-access tier: allow directly but keep the catastrophic rm guard ────────────────────
    // (the old AUTO session mode's "allow everything" semantics now live in the full-access tier; AUTO is gone)

    @Test
    fun fullAccessLevel_allowsAnyTool() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun fullAccessLevel_allowsBash() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun fullAccessLevel_stillBlocksCatastrophicRm() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertTrue(r.denyReason?.startsWith("Safety: high-risk delete blocked (root directory)") == true)
    }

    @Test
    fun fullAccessLevel_stillBlocksWorkspaceRm() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf ~/workspace/*"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun disabledSafety_allModes_allowsCatastrophicRm() = runTest {
        // "Disable safety interception" now applies to all modes (the AUTO-only switch went away with AUTO)
        val e = engine(safetyDisabled = true, level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── ASK mode: writes are hard-denied, and the AI may not switch itself into PLAN ─────────────

    @Test
    fun askMode_deniesWriteTool() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.ASK)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun askMode_deniesBash() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.ASK)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun askMode_deniesPlanModeSwitch() = runTest {
        // planMode's capability is not in the dangerous set, so it needs a dedicated block; otherwise it would run after a prompt
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.MODIFY_SESSION_STATE),
            "planMode",
            mapOf("action" to JsonPrimitive("enter")),
            AgentMode.ASK
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun askMode_allowsReadOnlyTool() = runTest {
        val e = engine(
            PermissionRule("list", PermissionRule.WHOLE_TOOL, PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.READ_WORKSPACE), "list", emptyMap(), AgentMode.ASK)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun planMode_deniesLinuxMachineWrite() = runTest {
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS, ToolCapability.NETWORK_WRITE, ToolCapability.EXTERNAL_TOOL),
            "linux_machine",
            mapOf("action" to JsonPrimitive("run_command")),
            AgentMode.PLAN
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun planMode_allowsLinuxMachineRead() = runTest {
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.NETWORK_READ),
            "linux_machine",
            mapOf("action" to JsonPrimitive("info")),
            AgentMode.PLAN
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    @Test
    fun askMode_deniesLinuxMachineWrite() = runTest {
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS, ToolCapability.NETWORK_WRITE, ToolCapability.EXTERNAL_TOOL),
            "linux_machine",
            mapOf("action" to JsonPrimitive("files_write")),
            AgentMode.ASK
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun askMode_allowsLinuxMachineRead() = runTest {
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.NETWORK_READ),
            "linux_machine",
            mapOf("action" to JsonPrimitive("files_list")),
            AgentMode.ASK
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── Read-only agent config auto-allow ─────────────────────────────────────

    @Test
    fun readAgentConfig_capabilityAutoAllow() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_AGENT_CONFIG), "customTool", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── task read-only actions allowed; whole-tool DENY still applies ─────────────────────────

    @Test
    fun taskReadAction_allowsWithoutRules() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXTERNAL_TOOL), "task", terminal("read"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun taskReadAction_alwaysAllowed() = runTest {
        // task's read-only actions take evaluate's fast-allow branch, ahead of tier and rule matching, so rules do not affect the result
        val e = engine(
            PermissionRule("task", PermissionRule.WHOLE_TOOL, PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.EXTERNAL_TOOL), "task", terminal("read"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── Remembered rules are gone: shell authorization is decided by "tier + high-risk rules" only ──────────

    @Test
    fun shellDenyRuleIgnored_authLevelDecides() = runTest {
        // ALLOW/DENY remembered rules for command tools are fully retired (they used to apply before the tier and bypass high-risk interception),
        // ls hits the built-in read-only allowlist -> allowed; the DENY rule plays no part.
        val e = engine(
            PermissionRule("Bash", "ls", PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── Not statically analyzable: allowed when there are no destructive traits ───────────────────────────────

    @Test
    fun unanalyzableBenignCommand_allowed() = runTest {
        // Command substitution defeats static analysis, but a destructive-trait scan of the raw text finds nothing -> allowed (AI diagnostic commands often contain $())
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("echo $(whoami)"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── Built-in safe allowlist auto-allow ───────────────────────────────────────

    @Test
    fun safeCommand_autoAllowed() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun mixOfSafeAndNonHighRisk_allowed() = runTest {
        // High-risk rules only cover recursive/wildcard/bulk deletion, so bare `rm x` does not hit; the whole command is statically analyzable -> allowed.
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la && rm x"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── Remembered ALLOW rules ───────────────────────────────────────────

    @Test
    fun rememberedPrefix_allows() = runTest {
        val e = engine(
            PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git pull origin main"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun rememberedPrefix_doesNotMatchOtherSubcommand() = runTest {
        // "Rule-based" tier semantics: git clone hits no high-risk rule and is statically analyzable -> allowed outright (no per-run prompting);
        // This also verifies the "git pull" remembered rule does not wrongly allow other git subcommands that high-risk rules would then block.
        val e = engine(
            PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git clone https://x"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── rm fine-grained checks: rules with no target / recursive / wildcard must not allow ───────────────

    @Test
    fun bareRmRuleIgnored_nonRecursiveRmAllowed() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm file.txt"), AgentMode.BUILD)
        // Rules play no part; `rm file.txt` is non-recursive with no wildcard and hits no high-risk rule -> allowed
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun nonRecursiveRmRule_doesNotAllowRecursiveRm() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm file.txt", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /some/dir"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty()) // recursive deletion is not rememberable
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun recursiveRmRuleIgnored_stillAsks() = runTest {
        // ALLOW remembered rules are gone; recursive deletion hits a high-risk rule -> manual confirmation, not rememberable
        val e = engine(
            PermissionRule("Bash", "rm -rf /tmp/build", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /tmp/build"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    // ── Catastrophic rm guard also applies in BUILD mode ─────────────────────────

    @Test
    fun catastrophicRm_deniedEvenWithAllowRule() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm -rf", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun systemDirRm_denied() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /etc"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── Elevation: carrying elevate downgrades a hard deny to one-time authorization ───────────────────

    @Test
    fun catastrophicRm_elevate_asksUser() = runTest {
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            mapOf("command" to JsonPrimitive("rm -rf /"), "elevate" to JsonPrimitive(true)),
            AgentMode.BUILD
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.askTitle)
    }

    @Test
    fun catastrophicRm_denyReasonHintsElevate() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertTrue(r.denyReason?.contains("elevate") == true)
    }

    @Test
    fun catastrophicRm_fullAccessLevel_elevate_asksUser() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            mapOf("command" to JsonPrimitive("rm -rf /"), "elevate" to JsonPrimitive(true)),
            AgentMode.BUILD
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── Path normalization: many equivalent spellings must be blocked; workspace subdirectories allowed ─────────────

    private suspend fun denied(command: String): Boolean =
        engine().evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash(command), AgentMode.BUILD).verdict ==
            ToolPermissionPolicyEngine.Verdict.DENY

    @Test
    fun abnormalPathSpellings_denied() = runTest {
        assertTrue(denied("rm -rf //etc"))
        assertTrue(denied("rm -rf /./etc"))
        assertTrue(denied("rm -rf /etc/../etc"))
        assertTrue(denied("rm -rf /tmp/../etc"))
        assertTrue(denied("rm -rf /foo/../"))
        assertTrue(denied("rm -rf \$HOME"))
        assertTrue(denied("rm -rf \${HOME}/foo"))
        assertTrue(denied("rm -rf /et*"))
        assertTrue(denied("rm -rf /usr*"))
        assertTrue(denied("rm -rf /srv"))
        assertTrue(denied("rm -rf /mnt"))
    }

    @Test
    fun workspaceSubdir_allowed() = runTest {
        assertTrue(!denied("rm -rf ~/workspace/build"))
        assertTrue(!denied("rm -rf ~/workspace/app/src"))
        assertTrue(!denied("rm -rf /tmp/build"))
    }

    @Test
    fun workspaceRoot_stillDenied() = runTest {
        assertTrue(denied("rm -rf ~/workspace"))
        assertTrue(denied("rm -rf ~/workspace/*"))
        assertTrue(denied("rm -rf ~/workspace/.."))
    }

    // ── Full-access tier: statically unanalyzable commands are allowed verbatim ─────────────────────

    @Test
    fun fullAccess_unanalyzableBenign_allowed() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("echo \$(date)"), AgentMode.BUILD).verdict
        )
    }

    @Test
    fun fullAccess_unanalyzableBenignWithStderrRedirect_allowed() = runTest {
        // Regression (reproduced on a real device 10-18): the old AUTO branch used the strict "contains `>` means destructive" check, while
        // ShellCommandParser classifies `> /dev/null` as not statically analyzable — the two conditions stacked,
        // and every read-only diagnostic command carrying `2>/dev/null` (getprop/pidof/cat /proc/...) got
        // hard-denied. Now unified into the lenient check (only find -delete / destructive programs / overwrite redirects count),
        // and full access allows verbatim, with no second fallback interception.
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            bash("id; getprop ro.product.cpu.abi; getenforce 2>/dev/null; cat /proc/sys/kernel/perf_event_paranoid 2>/dev/null; echo \"--- dfm proc ---\"; pidof com.tencent.tmgp.dfm 2>/dev/null || echo \"not running\"; echo \"--- dfm installed ---\"; ls /data/app/ | grep -i tmgp.dfm"),
            AgentMode.BUILD
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun ruleBased_unanalyzableDestructiveInSubstitution_stillAsks() = runTest {
        // The safety boundary is not loosened by the false-positive fix: find -delete hidden inside command substitution still requires manual confirmation in the rule-based tier
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            bash("echo \$(find /data -name '*.tmp' -delete)"),
            AgentMode.BUILD
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun safetyDisabled_unanalyzable_allowed() = runTest {
        val e = engine(safetyDisabled = true, level = CommandAuthLevel.FULL_ACCESS)
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("cat > /etc/passwd"), AgentMode.BUILD).verdict
        )
    }

    // ── Shizuku high-risk tools: always prompt, not rememberable, no exemption even with full access ─────────

    private fun shizuku(command: String) = mapOf("command" to JsonPrimitive(command))

    @Test
    fun shizuku_buildMode_asksWithoutRememberable() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun shizuku_safeCommandStillAsks() = runTest {
        val e = engine()
        // The built-in safe allowlist (ls) does not apply to Shizuku; still prompts
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("ls -la /sdcard"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_rememberedAllowRuleIgnored() = runTest {
        val e = engine(PermissionRule("Shizuku", "pm", PermissionDecision.ALLOW))
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_denyRuleStillAsks() = runTest {
        // Shizuku always takes the forceAsk branch (prompt, not rememberable); the DENY rule plays no part -> the result is ASK
        val e = engine(PermissionRule("Shizuku", "pm", PermissionDecision.DENY))
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_fullAccess_asks() = runTest {
        // Full access does not exempt Shizuku from per-run confirmation
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun shizuku_safetyDisabled_allows() = runTest {
        // "Disable safety interception" applies to all modes and tiers: Shizuku no longer needs per-run confirmation either
        val e = engine(safetyDisabled = true, level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun shizuku_planMode_denied() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── Non-shell tools ───────────────────────────────────────────────

    @Test
    fun genericTool_wholeRuleAllows() = runTest {
        val e = engine(
            PermissionRule("writeFile", PermissionRule.WHOLE_TOOL, PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun genericTool_unrememberableCapability_asksOnce() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.MODIFY_AGENT_CONFIG), "editFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun genericTool_wholeDenyRule_asks() = runTest {
        // Non-shell tools only honor whole-tool ALLOW remembering; DENY rules produce no hard deny -> it falls through to the default whole-tool ASK
        val e = engine(
            PermissionRule("writeFile", PermissionRule.WHOLE_TOOL, PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertEquals(listOf(PermissionRule.WHOLE_TOOL), r.rememberablePatterns)
    }

    // ── remember: dedupe then persist one by one ────────────────────────────────────

    @Test
    fun remember_dedupesAndAddsEach() = runTest {
        val repo = mockk<PermissionRulesRepository>(relaxed = true)
        coEvery { repo.add(any(), any()) } just runs
        val e = ToolPermissionPolicyEngine(repo, mockk(relaxed = true), mockk(relaxed = true))

        e.remember("Bash", listOf("git pull", "git pull", "ls"), PermissionScope.PROJECT)

        coVerify(exactly = 2) { repo.add(PermissionScope.PROJECT, any()) }
        coVerify { repo.add(PermissionScope.PROJECT, PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)) }
        coVerify { repo.add(PermissionScope.PROJECT, PermissionRule("Bash", "ls", PermissionDecision.ALLOW)) }
    }

    // ── "Rule-based" tier: high-risk hit -> manual confirmation; ordinary commands allowed outright ─────────────

    @Test
    fun ruleBased_highRiskHit_asksWithRuleTitle() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /tmp/build"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.askTitle?.startsWith("High-risk rule") == true)
        // recursive deletion is not rememberable
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun ruleBased_nonRecursiveRmWithoutRuleHit_allows() = runTest {
        // Bare single-file rm is outside high-risk rule coverage (they only cover recursive/wildcard/bulk), and is statically analyzable -> allowed
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm file.txt"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun ruleBased_allRulesDisabled_allows() = runTest {
        // With all high-risk rules disabled by the user, statically analyzable ordinary commands are allowed outright
        val e = engine(highRiskRules = HighRiskRulePresets.RULES.map { it.copy(enabled = false) })
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm file.txt"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun ruleBased_unanalyzableBenign_allowed() = runTest {
        // No high-risk hit and not statically analyzable (contains command substitution) -> scan the raw text for destructive traits; allowed if nothing hits
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("echo $(whoami)"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun ruleBased_unanalyzableDestructive_asks() = runTest {
        // Not statically analyzable and the raw text has destructive traits -> manual confirmation, not rememberable
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf $(echo /tmp/x)"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    // ── "Full access" tier: allow directly, keeping only the catastrophic guard and the optional fallback ──────────────────

    @Test
    fun fullAccess_allowsHighRiskCommand() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git reset --hard origin/main"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun fullAccess_unanalyzableDestructive_isAllowed() = runTest {
        // "Full access" executes verbatim: everything except the catastrophic guard is allowed, with no second fallback interception
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("cat > /etc/passwd"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun fullAccess_safetyDisabled_allowsUnanalyzable() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS, safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("cat > /etc/passwd"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun fullAccess_allowsNonDestructiveCommandWithStderrRedirect() = runTest {
        // Regression: looksDestructive used to flag any command containing `>` as destructive,
        // so "not statically analyzable + 2>/dev/null" diagnostic commands unrelated to deletion got hard-denied.
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            bash("cat $(find . -name '*.log') 2>/dev/null | head -50"),
            AgentMode.BUILD
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun fullAccess_safetyDisabled_allowsCatastrophicRm() = runTest {
        // "Disable safety interception" now applies to all modes and tiers (the AUTO-only switch went away with AUTO)
        val e = engine(level = CommandAuthLevel.FULL_ACCESS, safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── "Autonomous" tier: uniformly marks aiReviewable for AI review at the workflow layer ─────────────

    @Test
    fun autonomous_marksAiReviewable() = runTest {
        val e = engine(level = CommandAuthLevel.AUTONOMOUS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git clone https://x"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.aiReviewable)
        // "Always allow" remembering for commands is gone; only single-run approval is supported
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun autonomous_safeCommand_stillAutoAllowed() = runTest {
        // The built-in allowlist takes precedence over tier routing: ls is still allowed outright, skipping AI review
        val e = engine(level = CommandAuthLevel.AUTONOMOUS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun autonomous_recursiveRm_reviewableButNotRememberable() = runTest {
        val e = engine(level = CommandAuthLevel.AUTONOMOUS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /tmp/build"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.aiReviewable)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun autonomous_catastrophicRm_stillDenied() = runTest {
        // The catastrophic guard runs before tier routing; autonomous execution cannot bulldoze through it
        val e = engine(level = CommandAuthLevel.AUTONOMOUS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /etc"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }
}