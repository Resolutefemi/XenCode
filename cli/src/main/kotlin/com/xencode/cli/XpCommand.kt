package com.xencode.cli

import com.github.ajalt.clikt.core.requireObject
import com.xencode.core.provider.PcConfigStore

/**
 * `xencode xp` — level, totals, streak and recent events from the shared
 * database (`~/.xencode/aicode_agent_db`, xp_events — the same table the
 * Android app and the desktop app write; the legacy JSON ledger is imported
 * on first use). Columns mirror the Android `/xp` table (see XpSupport.kt).
 */
class XpCommand : XeCommand(
    name = "xp",
    helpText = "Show XP level, streak, and recent events"
) {
    override fun run() {
        val summary = MachineStores.sharedXpStore().summary()
        echo(renderXpSummary(summary))
        echo()
        echo("_Shared DB: ${MachineStores.sharedDbPath()}_")
        echo("_Config: ${PcConfigStore.defaultPath()}_")
    }
}
