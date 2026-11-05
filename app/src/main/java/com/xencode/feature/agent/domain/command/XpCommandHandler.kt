package com.xencode.feature.agent.domain.command

import com.xencode.R
import javax.inject.Inject

/**
 * /xp — level, total and today's XP, streak, and the most recent award events.
 */
class XpCommandHandler @Inject constructor() : ActionCommandHandler {
    override val name = "xp"
    override val descriptionRes = R.string.slash_command_xp_desc

    override fun execute(context: SlashCommandContext, args: String) {
        context.showXp()
    }
}
