package com.xencode.feature.agent.domain.command

import com.xencode.R
import javax.inject.Inject

/**
 * /goal — manage the workspace goal DAG without a model turn.
 *
 * Usage:
 * - `/goal`                      list goals with status and progress
 * - `/goal add <title> [after:g2 g5] [note:<text>]`   add a goal
 * - `/goal done g3 [note:<text>]` mark DONE (+50 XP)
 * - `/goal fail g3` / `/goal retry g3` / `/goal drop g3` / `/goal start g3`
 */
class GoalCommandHandler @Inject constructor() : ActionCommandHandler {
    override val name = "goal"
    override val descriptionRes = R.string.slash_command_goal_desc
    override val acceptsArgs = true

    override fun execute(context: SlashCommandContext, args: String) {
        context.manageGoals(args)
    }
}
