package com.xencode.feature.agent.domain.command

import com.xencode.R
import com.xencode.feature.agent.domain.prompt.SystemPromptProvider
import javax.inject.Inject

/**
 * /init — triggers one agent turn that analyzes the current workspace codebase and
 * generates/improves the project rules file (`AGENTS.md`; improves `CLAUDE.md` if that is all there is).
 *
 * The command body lives in `prompts/agent/init.md` and can be overridden by `~/.xencode/prompts.custom/agent/init.md`.
 */
class InitCommandHandler @Inject constructor(
    private val systemPromptProvider: SystemPromptProvider
) : ActionCommandHandler {
    override val name = "init"
    override val descriptionRes = R.string.slash_command_init_desc
    override val acceptsArgs = true

    override fun execute(context: SlashCommandContext, args: String) {
        context.initProject(substituteArguments(systemPromptProvider.resolvePrompt(PROMPT_NAME), args))
    }

    companion object {
        /** Command body snippet name, matching the path under `prompts/`. */
        const val PROMPT_NAME = "agent/init.md"
    }
}
