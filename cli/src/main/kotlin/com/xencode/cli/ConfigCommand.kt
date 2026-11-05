package com.xencode.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.xencode.core.provider.PcConfig
import com.xencode.core.provider.PcConfigStore
import com.xencode.core.provider.ProviderConfig
import java.io.File

/**
 * `xencode config` — manage `~/.xencode/pc-config.json`.
 *
 * Subcommands: list / add / use / rm / set-workspace. Every mutation goes
 * through [ProviderConfig.validate] first; rejections print the reason.
 */
class ConfigCommand : XeCommand(
    name = "config",
    helpText = "Manage providers and the default workspace at ~/.xencode/pc-config.json"
) {
    init {
        subcommands(ListProviders(), AddProvider(), UseProvider(), RemoveProvider(), SetWorkspace())
    }

    override fun run() {
        // Required (run is abstract), but intentionally empty: when no subcommand is
        // given Clikt prints this help text instead of running, and with a subcommand
        // this run() merely precedes the invoked subcommand's own run().
    }
}

/** Masked provider listing used by `config list`. */
private fun CliktCommand.echoProviderList(config: PcConfig) {
    echo("Config file: ${PcConfigStore.defaultPath()}")
    if (config.providers.isEmpty()) {
        echo("No providers configured. Add one with:")
        echo("  xencode config add --name <name> --kind openai|anthropic --base-url <url> \\")
        echo("      --api-key <key> --model <model>")
        echo("Default workspace: ${config.defaultWorkspace ?: "(not set)"}")
        return
    }
    for (p in config.providers) {
        val activeMark = if (config.activeProvider()?.name == p.name) " (active)" else ""
        val invalid = p.validate()?.let { "  [invalid: $it]" } ?: ""
        echo("- ${p.name}$activeMark kind=${p.kind} model=${p.model}")
        echo("    baseUrl=${p.baseUrl}")
        echo("    apiKey=${maskKey(p.apiKey)}")
        p.temperature?.let { echo("    temperature=$it") }
        p.maxTokens?.let { echo("    maxTokens=$it") }
        if (p.extraHeaders.isNotEmpty()) echo("    headers=${p.extraHeaders.keys.joinToString(", ")}")
        if (invalid.isNotEmpty()) echo(invalid)
    }
    echo("Default workspace: ${config.defaultWorkspace ?: "(not set)"}")
}

/** `config list` — masked provider table. */
private class ListProviders : XeCommand(
    name = "list",
    helpText = "List configured providers with masked API keys"
) {
    override fun run() {
        echoProviderList(PcConfigStore.load())
    }
}

/** `config add` — validate then persist a new provider. */
private class AddProvider : XeCommand(
    name = "add",
    helpText = "Add a provider (validated before saving)"
) {
    private val name by option(help = "Provider name (unique, e.g. zai, openrouter)")
        .default("")
    private val kind by option(help = "openai (chat-completions compatible) or anthropic (native Messages)")
        .default("openai")
    private val baseUrl by option(help = "API base URL, http(s)://...").default("")
    private val apiKey by option(help = "API key (use a dummy like 'none' for local endpoints)")
        .default("")
    private val model by option(help = "Model id, e.g. glm-4.6").default("")
    private val temperature by option(help = "Sampling temperature (optional)").double()
    private val maxTokens by option(help = "Max tokens (optional)").int()
    private val headers by option(
        "--header",
        help = "Extra header as k=v (repeatable)"
    ).multiple()

    override fun run() {
        if (name.isBlank() || baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            throw UsageError("--name, --base-url, --api-key and --model are required. See --help.")
        }
        val (extraHeaders, headerError) = parseHeaderPairs(headers)
        if (headerError != null) throw UsageError(headerError)

        val existing = PcConfigStore.load()
        if (isDuplicateName(existing.providers, name)) {
            throw UsageError("Provider `$name` already exists. Remove it first with `xencode config rm --name $name`.")
        }

        val provider = buildProvider(
            name, kind, baseUrl, apiKey, model, temperature, maxTokens, extraHeaders
        )
        val rejection = provider.validate()
        if (rejection != null) {
            throw UsageError("Rejected: $rejection")
        }

        val next = PcConfig(
            providers = existing.providers + provider,
            active = existing.active ?: provider.name,
            defaultWorkspace = existing.defaultWorkspace
        )
        if (!PcConfigStore.save(next)) {
            echo("Could not write ${PcConfigStore.defaultPath()}", err = true)
            throw UsageError("save failed")
        }
        echo("Provider `${provider.name}` added (kind=${provider.kind}, model=${provider.model}, apiKey=${maskKey(provider.apiKey)}).")
        echo("Validate any time with `xencode doctor`; make it the default with `xencode config use --name ${provider.name}`.")
    }
}

/** `config use` — set the active provider. */
private class UseProvider : XeCommand(
    name = "use",
    helpText = "Set the active provider"
) {
    private val name by option(help = "Provider name").default("")

    override fun run() {
        if (name.isBlank()) throw UsageError("--name is required")
        val existing = PcConfigStore.load()
        val match = existing.providers.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw UsageError("No provider named `$name`. Use `xencode config list` to see them.")
        val next = PcConfig(
            providers = existing.providers,
            active = match.name,
            defaultWorkspace = existing.defaultWorkspace
        )
        if (!PcConfigStore.save(next)) throw UsageError("save failed")
        echo("Active provider: ${match.name} (model ${match.model}, baseUrl ${match.baseUrl})")
    }
}

/** `config rm` — remove a provider. */
private class RemoveProvider : XeCommand(
    name = "rm",
    helpText = "Remove a provider"
) {
    private val name by option(help = "Provider name").default("")

    override fun run() {
        if (name.isBlank()) throw UsageError("--name is required")
        val existing = PcConfigStore.load()
        val match = existing.providers.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw UsageError("No provider named `$name`.")
        val wasActive = existing.activeProvider()?.name == match.name
        val next = PcConfig(
            providers = existing.providers.filter { it != match },
            active = if (existing.active == match.name) null else existing.active,
            defaultWorkspace = existing.defaultWorkspace
        )
        if (!PcConfigStore.save(next)) throw UsageError("save failed")
        echo("Removed `${match.name}`.")
        if (wasActive) {
            val fallback = next.activeProvider()
            if (fallback != null) echo("Active was cleared; falls back to `${fallback.name}` (first remaining).")
            else echo("No providers left; add one with `xencode config add`.")
        }
    }
}

/** `config set-workspace` — default workspace used when --workspace is omitted. */
private class SetWorkspace : XeCommand(
    name = "set-workspace",
    helpText = "Set the default workspace used when --workspace is omitted"
) {
    private val path by option(help = "Absolute or relative workspace path").default("")

    override fun run() {
        if (path.isBlank()) throw UsageError("--path is required")
        val absolute = File(path).absoluteFile.normalize().path
        if (!File(absolute).isDirectory) {
            throw UsageError("Directory does not exist: $absolute")
        }
        val existing = PcConfigStore.load()
        val next = PcConfig(
            providers = existing.providers,
            active = existing.active,
            defaultWorkspace = absolute
        )
        if (!PcConfigStore.save(next)) throw UsageError("save failed")
        echo("Default workspace: $absolute (applies to goal/dag/xp/chat/doctor when --workspace is omitted).")
    }
}
