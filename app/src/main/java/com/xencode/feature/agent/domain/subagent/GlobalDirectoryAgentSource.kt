package com.xencode.feature.agent.domain.subagent

import com.xencode.feature.workspace.domain.FileAccessProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global sub-agent definition source: `~/.xencode/agents`, shared across projects. Accessed via [FileAccessProvider] with container paths,
 * following the current execution environment — in local mode it lands in the app-private `filesDir/xencode/agents` (`/root/.xencode/agents` inside the container),
 * in remote mode it lands under the server user's home via SSH at `.xencode/agents`. Remote is a separate execution environment; reads and writes follow the remote side.
 */
@Singleton
class GlobalDirectoryAgentSource @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AgentDefinitionSource {

    val agentsRoot: String = "~/.xencode/agents"

    override fun listDefinitions(): List<AgentDefinition> =
        AgentDefinitionDirectoryScanner.scan(fileAccess, agentsRoot)
}
