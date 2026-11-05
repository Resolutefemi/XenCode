package com.xencode.cli

import com.github.ajalt.clikt.core.requireObject
import com.xencode.core.provider.PcConfigStore

/**
 * `xencode xp` — level, totals, streak and recent events from the per-machine
 * ledger at `~/.xencode/pc-xp-ledger.json`. Columns mirror the Android `/xp`
 * table (see XpSupport.kt).
 */
class XpCommand : XeCommand(
    name = "xp",
    helpText = "Show XP level, streak, and recent events"
) {
    override fun run() {
        val summary = MachineStores.ledger().summary()
        echo(renderXpSummary(summary))
        echo()
        echo("_Ledger: ${MachineStores.ledgerFile().path}_")
        echo("_Config: ${PcConfigStore.defaultPath()}_")
    }
}
