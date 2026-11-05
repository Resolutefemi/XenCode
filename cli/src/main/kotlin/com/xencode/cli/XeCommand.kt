package com.xencode.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

/**
 * Small shared base for all XenCode commands: Clikt 5 moved the description
 * from the constructor to an overridable [CliktCommand.help] function, so this
 * restores one-argument ergonomics and keeps help strings next to the names.
 */
abstract class XeCommand(
    name: String,
    private val helpText: String
) : CliktCommand(name) {
    override fun help(context: Context): String = helpText
}
