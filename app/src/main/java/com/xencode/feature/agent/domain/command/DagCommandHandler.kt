package com.xencode.feature.agent.domain.command

import com.xencode.R
import javax.inject.Inject

/**
 * /dag — render the workspace goal DAG (from `.xencode/GOAL_DAG.json`) as a Markdown
 * outline with status glyphs, ready state and blockers. Read-only; use /goal to mutate.
 */
class DagCommandHandler @Inject constructor() : ActionCommandHandler {
    override val name = "dag"
    override val descriptionRes = R.string.slash_command_dag_desc

    override fun execute(context: SlashCommandContext, args: String) {
        context.showDag()
    }
}
