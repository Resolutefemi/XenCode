package com.xencode.feature.agent.domain.command

import com.xencode.R
import javax.inject.Inject

/**
 * /compress — manually triggers context compaction for the current session,
 * reusing ContextCompactor's compaction logic.
 */
class CompressCommandHandler @Inject constructor() : ActionCommandHandler {
    override val name = "compress"
    override val descriptionRes = R.string.slash_command_compress_desc

    override fun execute(context: SlashCommandContext, args: String) {
        context.compactCurrentSession()
    }
}
