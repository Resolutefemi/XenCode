package com.xencode.feature.agent.domain.skill

import com.xencode.feature.workspace.domain.FileAccessProvider
import com.xencode.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Project-level skill source: `<projectRoot>/.xencode/skills/`, travels with the workspace and can be tracked by git.
 * Accessed via [FileAccessProvider] with container paths: local maps to the host workspace, remote lands in the remote workspace over SSH.
 */
@Singleton
class ProjectDirectorySkillSource @Inject constructor(
    private val fileAccess: FileAccessProvider
) : SkillSource {

    val skillsRoot: String = "${WorkspacePathMapper.CONTAINER_ROOT}/.xencode/skills"

    override fun listSkills(): List<Skill> = SkillDirectoryScanner.scan(fileAccess, skillsRoot)

    override fun loadInstructions(name: String): String? =
        SkillDirectoryScanner.locateAndRead(fileAccess, skillsRoot, name)
}
