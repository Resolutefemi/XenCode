package com.xencode.cli

import com.github.ajalt.clikt.core.requireObject
import com.xencode.core.provider.PcConfigStore
import java.io.File

/**
 * `xencode init` — bootstrap `~/.xencode/` with a starter pc-config.json and,
 * when the workspace has none yet, a suggested AGENTS.md.
 *
 * The starter config is empty (`"providers": []`, `"active": null`); JSON has
 * no comments, so the worked example is printed to stdout instead and points
 * at `xencode config add`, which is the supported way to fill this file.
 */
class InitCommand : XeCommand(
    name = "init",
    helpText = "Create ~/.xencode/ with a starter config, and an AGENTS.md in the workspace"
) {
    private val runtime by requireObject<CliRuntime>()

    override fun run() {
        // --- ~/.xencode/pc-config.json ---
        val home = MachineStores.xencodeHome()
        if (!home.isDirectory) home.mkdirs()
        val configFile = File(home, "pc-config.json")
        if (configFile.isFile) {
            echo("[ok] config already exists: ${configFile.path} (left untouched)")
        } else {
            configFile.writeText(STARTER_CONFIG, Charsets.UTF_8)
            echo("[ok] wrote ${configFile.path}")
        }

        // --- README-style comment (stdout; JSON itself cannot carry comments) ---
        val config = PcConfigStore.load()
        echo()
        if (config.providers.isEmpty()) {
            echo("No providers are configured yet. Do not edit the JSON by hand — use:")
        } else {
            echo("Providers live in this file; manage them with (active: ${config.activeProvider()?.name ?: "none"}):")
        }
        echo()
        echo("  xencode config add --name <name> --kind openai|anthropic --base-url <url> \\")
        echo("      --api-key <key> --model <model> [--temperature 0.2] [--max-tokens 4096] [--header k=v]")
        echo()
        echo("Example (OpenAI-compatible endpoint):")
        echo()
        echo("  xencode config add --name zai --kind openai --base-url https://api.z.ai/api/v1 \\")
        echo("      --api-key sk-xxxxxxxx --model glm-4.6")
        echo()
        echo("The first provider added becomes the active one; switch later with `xencode config use --name zai`.")
        echo("Verify everything with `xencode doctor`.")

        // --- workspace AGENTS.md ---
        val agents = File(runtime.workspaceRoot, "AGENTS.md")
        if (agents.isFile) {
            echo()
            echo("[ok] AGENTS.md already exists in ${runtime.workspaceRoot} (left untouched)")
        } else {
            agents.writeText(AGENTS_TEMPLATE.trimIndent() + "\n", Charsets.UTF_8)
            echo()
            echo("[ok] wrote ${agents.path}")
        }
    }

    private companion object {
        val STARTER_CONFIG = """
            {
              "providers": [],
              "active": null,
              "defaultWorkspace": null
            }
        """.trimIndent() + "\n"

        val AGENTS_TEMPLATE = """
            # AGENTS.md

            Guidelines for AI coding agents working in this workspace.

            - Workspace root: this directory. Keep every edit inside it.
            - Prefer small, verifiable steps; run builds/tests before claiming a task is done.
            - Match the style and language of the surrounding code; user-facing strings in English.
            - No emoji in code or output.
            - Track multi-step work with the goal DAG:
              `xencode goal add <title> [after g2]`, `xencode goal done <id>`, `xencode goal list`.
        """.trimIndent()
    }
}
