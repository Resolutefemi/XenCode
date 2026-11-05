package com.xencode.feature.agent.domain.skill

import com.xencode.feature.workspace.domain.FileAccessProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global skill source: `~/.xencode/skills`, shared across projects. Accessed via [FileAccessProvider] with container paths,
 * following the current execution environment — in local mode it lands in the app-private `filesDir/xencode/skills` (`/root/.xencode/skills` inside the container),
 * in remote mode it lands under the server user's home via SSH at `.xencode/skills`. Remote is a separate execution environment; reads and writes follow the remote side.
 */
@Singleton
class GlobalDirectorySkillSource @Inject constructor(
    private val fileAccess: FileAccessProvider
) : SkillSource {

    val skillsRoot: String = "~/.xencode/skills"

    override fun listSkills(): List<Skill> = SkillDirectoryScanner.scan(fileAccess, skillsRoot)

    override fun loadInstructions(name: String): String? =
        SkillDirectoryScanner.locateAndRead(fileAccess, skillsRoot, name)
}
