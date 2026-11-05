package com.xencode.feature.agent.domain.command

import androidx.annotation.StringRes
import com.xencode.feature.agent.domain.skill.Skill

/** Argument placeholder inside prompt-style command bodies. */
internal const val ARGUMENTS_PLACEHOLDER = "\$ARGUMENTS"

/**
 * Replaces `$ARGUMENTS` in the template with [args]; if the template has no placeholder
 * and [args] is non-empty, appends a line so no user input is lost.
 */
internal fun substituteArguments(template: String, args: String): String = when {
    template.contains(ARGUMENTS_PLACEHOLDER) -> template.replace(ARGUMENTS_PLACEHOLDER, args)
    args.isBlank() -> template
    else -> "$template\n\nAdditional user input: $args"
}

/** Slash command origin: a built-in command (local action) or a skill (injects the SKILL.md body into the current turn). */
enum class SlashCommandKind { ACTION, SKILL }

/**
 * Display model for the input box's `/` menu; a unified representation of built-in commands and skills.
 *
 * @param name Command name without the leading `/` (e.g. `status`, `pdf-report`).
 * @param description Resolved menu description (string resource for built-in commands, the skill's `description` for skills).
 * @param kind Origin type, used by the menu to differentiate display.
 * @param acceptsArgs Whether arguments are accepted; when true, tapping in the menu appends a space so arguments can be typed right away.
 */
data class SlashCommand(
    val name: String,
    val description: String,
    val kind: SlashCommandKind,
    val acceptsArgs: Boolean = false
)

/**
 * Built-in slash command handler (local-action type). Each command implements one of these,
 * collected into [SlashCommandRegistry] via Hilt `@Binds @IntoSet`.
 *
 * Adding a command only takes: a new implementation class + a matching method on [SlashCommandContext] + a ViewModel implementation +
 * one more binding line in [SlashCommandModule].
 */
interface ActionCommandHandler {

    /** Command name without the leading `/`. */
    val name: String

    /** Menu description, from the bilingual string resources. */
    @get:StringRes
    val descriptionRes: Int

    /** Whether arguments are accepted. Defaults to no: extra arguments cause a miss (falls back to a normal message, so user input is not swallowed). */
    val acceptsArgs: Boolean get() = false

    /** Executed on a hit. [args] is the raw text after the command name (already trimmed). */
    fun execute(context: SlashCommandContext, args: String)
}

/**
 * Command execution context: exposes only the minimal capabilities commands need, so commands never hold the ViewModel directly.
 * Implemented by AIAgentViewModel; when adding a command, add the method to this interface and implement it in the ViewModel.
 */
interface SlashCommandContext {
    fun compactCurrentSession()

    /** /usage — prints today's and all-time call counts, token usage, and estimated cost as a Markdown table. */
    fun showUsage()

    /** /init — triggers one agent turn that analyzes the codebase and generates/improves the project rules file; [prompt] is the command body. */
    fun initProject(prompt: String)

    /** Skill trigger: executes the [skill] body as this turn's instruction; [args] holds the extra arguments. */
    fun runSkill(skill: Skill, args: String)
}
