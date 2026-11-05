package com.xencode.feature.agent.domain.command

import com.xencode.R
import javax.inject.Inject

/**
 * /usage — view today's and all-time call counts, token usage, and estimated cost (global stats, across sessions).
 * The result is output as a Markdown table in an AI bubble.
 */
class UsageCommandHandler @Inject constructor() : ActionCommandHandler {
    override val name = "usage"
    override val descriptionRes = R.string.slash_command_usage_desc

    override fun execute(context: SlashCommandContext, args: String) {
        context.showUsage()
    }
}
