package com.xencode.feature.agent.domain.subagent

import com.xencode.feature.workspace.domain.FileAccessProvider
import com.xencode.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Project-level sub-agent definition source: `<projectRoot>/.xencode/agents/`, travels with the workspace and can be tracked by git.
 * Accessed via [FileAccessProvider] with container paths: local maps to the host workspace, remote lands in the remote workspace over SSH.
 */
@Singleton
class ProjectDirectoryAgentSource @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AgentDefinitionSource {

    val agentsRoot: String = "${WorkspacePathMapper.CONTAINER_ROOT}/.xencode/agents"

    override fun listDefinitions(): List<AgentDefinition> =
        AgentDefinitionDirectoryScanner.scan(fileAccess, agentsRoot)
}
