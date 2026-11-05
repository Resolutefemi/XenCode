package com.xencode.feature.agent.domain.command

import android.content.Context
import com.xencode.feature.agent.domain.skill.Skill
import com.xencode.feature.agent.domain.skill.SkillRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Slash command registry: aggregates built-in commands ([ActionCommandHandler]) and enabled skills,
 * uniformly serving the `/` menu display and send-time resolution.
 *
 * - Menu: [commands] is the merged [SlashCommand] list — built-in commands take priority, same-named skills are skipped, sorted by name.
 * - Resolve: [resolve] splits `/name args` into command name and arguments and matches a built-in command or skill; purely in-memory, no IO.
 * - Refresh: [refresh] rescans skills (file IO); the caller decides when to trigger it.
 */
@Singleton
class SlashCommandRegistry @Inject constructor(
    actionHandlers: Set<@JvmSuppressWildcards ActionCommandHandler>,
    private val skillRepository: SkillRepository,
    @param:ApplicationContext private val context: Context
) {
    private val actions: List<ActionCommandHandler> = actionHandlers.sortedBy { it.name.lowercase() }
    private val actionsByName: Map<String, ActionCommandHandler> =
        actions.associateBy { it.name.lowercase() }

    /** Skill snapshot: updated on the IO thread by [refresh]; read-only for [resolve]. */
    @Volatile
    private var skills: List<Skill> = emptyList()

    private val _commands = MutableStateFlow<List<SlashCommand>>(emptyList())

    /** Command list shown in the `/` menu. */
    val commands: StateFlow<List<SlashCommand>> = _commands.asStateFlow()

    /** Rescans skills and rebuilds the menu. Involves file IO; callers should invoke it in an IO context. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        skills = skillRepository.listSkills()
        _commands.value = buildCommands()
    }

    private fun buildCommands(): List<SlashCommand> = buildList {
        actions.forEach {
            add(
                SlashCommand(
                    name = it.name,
                    description = context.getString(it.descriptionRes),
                    kind = SlashCommandKind.ACTION,
                    acceptsArgs = it.acceptsArgs
                )
            )
        }
        skills.filterNot { it.name.lowercase() in actionsByName }
            .sortedBy { it.name.lowercase() }
            .forEach {
            add(
                SlashCommand(
                    name = it.name,
                    description = it.description,
                    kind = SlashCommandKind.SKILL,
                    acceptsArgs = true
                )
            )
        }
    }

    /**
     * Resolves the sent text: returns non-null only when the input starts with `/` and matches a built-in command or skill.
     * When a built-in command does not accept arguments, supplying arguments counts as a miss (falls back to a normal message).
     */
    fun resolve(input: String): ResolvedCommand? {
        val trimmed = input.trim()
        if (!trimmed.startsWith('/')) return null
        val body = trimmed.substring(1)
        val name = body.substringBefore(' ').trim()
        if (name.isEmpty()) return null
        val args = body.substringAfter(' ', "").trim()

        actionsByName[name.lowercase()]?.let { handler ->
            if (!handler.acceptsArgs && args.isNotEmpty()) return null
            return ResolvedCommand.Action(handler, args)
        }
        val skill = skills.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        return ResolvedCommand.SkillCommand(skill, args)
    }

    /** Resolution result: a built-in action or a skill. */
    sealed interface ResolvedCommand {
        val name: String
        val args: String

        data class Action(val handler: ActionCommandHandler, override val args: String) : ResolvedCommand {
            override val name: String get() = handler.name
        }

        data class SkillCommand(val skill: Skill, override val args: String) : ResolvedCommand {
            override val name: String get() = skill.name
        }
    }

    companion object {
        /**
         * Builds the skill's instruction for this turn: replaces `$ARGUMENTS` in the skill body with [args];
         * when there is no placeholder and [args] is non-empty, appends a line so no user input is lost.
         */
        fun buildSkillPrompt(skill: Skill, args: String): String =
            substituteArguments(skill.instructions, args)
    }
}
