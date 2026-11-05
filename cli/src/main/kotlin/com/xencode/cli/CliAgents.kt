package com.xencode.cli

import com.github.ajalt.clikt.core.UsageError
import com.xencode.core.agent.AgentRuntime
import com.xencode.core.provider.ChatClient
import com.xencode.core.provider.PcConfigStore
import com.xencode.core.provider.ProviderConfig
import java.io.File

/**
 * Shared wiring for every agent-touching CLI command: resolve the active
 * provider, build the [AgentRuntime] for the resolved workspace, and report a
 * helpful error when nothing is configured yet.
 */
object CliAgents {

    fun activeProvider(): ProviderConfig =
        PcConfigStore.load().activeProvider()
            ?: throw UsageError(
                "No provider configured yet. Add one first:\n" +
                    "  xencode config add --name <name> --kind openai|anthropic|gemini --base-url <url> \\\n" +
                    "      --api-key <key> --model <model>\n" +
                    "Then run `xencode doctor` to verify it."
            )

    fun runtime(rt: CliRuntime, provider: ProviderConfig = activeProvider()): AgentRuntime =
        AgentRuntime(
            workspaceRoot = File(rt.workspaceRoot),
            provider = provider,
            client = ChatClient(),
            // XP awards (chat turns, goal runs) land in the shared database —
            // the same xp_events table the desktop app writes.
            xpStore = MachineStores.sharedXpStore()
        )
}
