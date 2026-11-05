package com.xencode.cli

import com.xencode.core.telemetry.XpLedger
import java.io.File

/**
 * Machine-wide (per-user) storage locations for the PC line.
 *
 * The XP ledger is deliberately per-machine (not synchronized with the Android
 * Room ledger): see the header comment in core's XpMath.kt. The config store
 * lives at `~/.xencode/pc-config.json` and is managed by core's PcConfigStore.
 */
object MachineStores {

    /** `~/.xencode`, the shared home for PC-line state. */
    fun xencodeHome(): File = File(homeDir(), ".xencode")

    /** Per-machine XP ledger at `~/.xencode/pc-xp-ledger.json`. */
    fun ledger(): XpLedger = XpLedger(root = homeDir() + "/.xencode")

    fun ledgerFile(): File = File(xencodeHome(), "pc-xp-ledger.json")

    private fun homeDir(): String =
        (System.getProperty("user.home") ?: ".").trimEnd('/').ifEmpty { "." }
}
