package com.xencode.feature.agent.domain.skill

import com.xencode.core.util.FileLogger
import com.xencode.feature.workspace.domain.FileAccessProvider
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Skill repository: aggregates skills from all [SkillSource]s (global directory + project directory)
 * and filters the injection list by [SkillConfigRepository]'s disabled list.
 *
 * Global skills live in `~/.xencode/skills`, project skills in `<workspace>/.xencode/skills`; both are read/written via [FileAccessProvider]
 * with container paths, following the current execution environment (local host directory or remote SSH workspace).
 */
@Singleton
class SkillRepository @Inject constructor(
    private val globalDirectorySkillSource: GlobalDirectorySkillSource,
    private val projectDirectorySkillSource: ProjectDirectorySkillSource,
    private val skillConfigRepository: SkillConfigRepository,
    private val fileAccess: FileAccessProvider
) {
    /** All skills (with source scope), not filtered by disabled state; for same-named skills, project wins (consistent with MCP's two-level config). */
    fun listAllSkills(): List<SkillEntry> =
        mergeAll(globalDirectorySkillSource.listSkills(), projectDirectorySkillSource.listSkills())

    /** Enabled skills (for injecting into the system prompt); disabled skills filtered out. */
    fun listSkills(): List<Skill> =
        filterDisabled(listAllSkills(), skillConfigRepository.disabledNames()).map { it.skill }

    /** Reads a skill's full instruction body; null when missing / unparseable / disabled. */
    fun loadInstructions(name: String): String? {
        if (name.lowercase() in skillConfigRepository.disabledNames()) return null
        return projectDirectorySkillSource.loadInstructions(name)
            ?: globalDirectorySkillSource.loadInstructions(name)
    }

    /** Whether the skill is disabled in any scope. */
    fun isSkillDisabled(name: String): Boolean =
        name.lowercase() in skillConfigRepository.disabledNames()

    /** Enables/disables a skill in the given scope. */
    fun setSkillDisabled(name: String, disabled: Boolean, scope: SkillScope) =
        skillConfigRepository.setDisabled(name, disabled, scope)

    /**
     * Writes the skill file (create or edit). [originalName] is the pre-edit name, null on create; returns null on success.
     *
     * On edit, write back to the original instruction file in the original directory (which may be CLAUDE.md); renaming only changes the frontmatter name, never the directory name —
     * skill bodies often reference sibling scripts as `~/.xencode/skills/<dir>/run.py`, and renaming along would break those references.
     */
    fun save(form: SkillForm, scope: SkillScope, originalName: String? = null): SkillSaveError? {
        val name = form.name.trim()
        if (!isValidName(name)) return SkillSaveError.INVALID_NAME
        if (form.instructions.isBlank()) return SkillSaveError.EMPTY_INSTRUCTIONS

        val entries = listAllSkills()
        val keepingName = originalName != null && originalName.equals(name, ignoreCase = true)
        if (!keepingName) {
            val taken = entries.any { it.scope == scope && it.skill.name.equals(name, ignoreCase = true) }
            if (taken) return SkillSaveError.NAME_CONFLICT
        }

        val existingDir = originalName?.let { old ->
            entries.firstOrNull {
                it.scope == scope && it.skill.name.equals(old, ignoreCase = true)
            }?.skill?.dirPath
        }

        val text = SkillParser.serialize(
            name = name,
            description = form.description,
            requiredTools = form.requiredTools,
            instructions = form.instructions
        )

        val provider = fileAccess
        return try {
            val dir = existingDir?.takeIf { provider.isDirectory(it) } ?: "${skillsRoot(scope)}/$name"
            provider.mkdirs(dir)
            val target = instructionFile(provider, dir) ?: "${dir.trimEnd('/')}/$INSTRUCTION_FILE"
            provider.writeFile(target, text, overwrite = true)
            null
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to save skill: $name", e)
            SkillSaveError.IO_FAILED
        }
    }

    /** Skill root directory for the given scope (container path). */
    fun skillsRoot(scope: SkillScope): String =
        if (scope == SkillScope.GLOBAL) {
            globalDirectorySkillSource.skillsRoot
        } else {
            projectDirectorySkillSource.skillsRoot
        }

    /**
     * Imports a skill from Markdown text into the given scope; [fallbackName] is the fallback when the frontmatter lacks a name.
     * An invalid name / name conflict / empty body fails the whole import; nothing is written.
     */
    fun importMarkdown(text: String, fallbackName: String, scope: SkillScope): SkillImportReport =
        SkillImporter.importMarkdown(fileAccess, skillsRoot(scope), existingNamesIn(scope), text, fallbackName)

    /** Imports skills from a zip input stream (may contain several skill directories) into the given scope. */
    fun importZip(input: InputStream, fallbackName: String, scope: SkillScope): SkillImportReport =
        SkillImporter.importArchive(fileAccess, skillsRoot(scope), existingNamesIn(scope), input, fallbackName)

    /** Existing skill names in the given scope (lowercase), for import duplicate checks. */
    private fun existingNamesIn(scope: SkillScope): Set<String> =
        listAllSkills().filter { it.scope == scope }.map { it.skill.name.lowercase() }.toSet()

    /** Deletes the skill in the given scope (removes its directory, unrecoverable). Returns whether it succeeded. */
    fun deleteSkill(name: String, scope: SkillScope): Boolean {
        val entry = listAllSkills().firstOrNull {
            it.skill.name.equals(name, ignoreCase = true) && it.scope == scope
        } ?: return false
        val dirPath = entry.skill.dirPath ?: return false
        return safeDeleteSkillDir(fileAccess, dirPath)
    }

    companion object {
        private const val TAG = "SkillRepository"

        /** Instruction file name written when creating a skill. */
        private const val INSTRUCTION_FILE = "SKILL.md"

        /** The name doubles as the directory name, so path separators and reserved characters are banned; the length cap guards against extreme directory names. */
        internal fun isValidName(name: String): Boolean {
            if (name.isBlank() || name.length > MAX_NAME_LENGTH) return false
            if (name == "." || name == "..") return false
            return name.none { it in ILLEGAL_NAME_CHARS || it.isISOControl() }
        }

        private const val MAX_NAME_LENGTH = 64
        private val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /** Full path of the instruction file already in the directory: SKILL.md first, then CLAUDE.md (same fallback chain as [SkillParser.parse]). */
        internal fun instructionFile(provider: FileAccessProvider, dirPath: String): String? {
            val files = runCatching { provider.listFiles(dirPath) }.getOrNull() ?: return null
            val name = files.firstOrNull { !it.isDirectory && it.name.equals("SKILL.md", ignoreCase = true) }?.name
                ?: files.firstOrNull { !it.isDirectory && it.name.equals("CLAUDE.md", ignoreCase = true) }?.name
                ?: return null
            return "${dirPath.trimEnd('/')}/$name"
        }

        /** Merges the two levels: same-named project skills override global, sorted by name. */
        internal fun mergeAll(global: List<Skill>, project: List<Skill>): List<SkillEntry> {
            val byName = LinkedHashMap<String, SkillEntry>()
            global.forEach { byName[it.name.lowercase()] = SkillEntry(it, SkillScope.GLOBAL) }
            project.forEach { byName[it.name.lowercase()] = SkillEntry(it, SkillScope.PROJECT) }
            return byName.values.sortedBy { it.skill.name.lowercase() }
        }

        /** Filters out disabled skills (the disabled list is already lowercase). */
        internal fun filterDisabled(entries: List<SkillEntry>, disabled: Set<String>): List<SkillEntry> =
            entries.filterNot { it.skill.name.lowercase() in disabled }

        /** Deletes only when the directory exists and contains a SKILL.md/CLAUDE.md instruction file, so non-skill directories are never deleted by mistake. */
        internal fun safeDeleteSkillDir(provider: FileAccessProvider, dirPath: String): Boolean {
            if (!provider.isDirectory(dirPath)) return false
            val hasInstruction = runCatching { provider.listFiles(dirPath) }.getOrNull()?.any {
                !it.isDirectory && (it.name.equals("SKILL.md", ignoreCase = true) || it.name.equals("CLAUDE.md", ignoreCase = true))
            } ?: false
            if (!hasInstruction) return false
            return runCatching { provider.deleteRecursively(dirPath); true }.getOrDefault(false)
        }
    }
}
