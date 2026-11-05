package com.xencode.feature.agent.domain.skill

import com.xencode.core.util.FileLogger
import com.xencode.feature.workspace.domain.FileAccessProvider
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Reasons a single skill import can fail. */
enum class SkillImportError {
    /** Name is empty, too long, or contains characters unusable in a directory name. */
    INVALID_NAME,

    /** A skill with the same name already exists in this scope. */
    NAME_CONFLICT,

    /** Instruction body is empty. */
    EMPTY_CONTENT,

    /** No skill found in the archive (missing SKILL.md / CLAUDE.md). */
    NO_SKILL_FOUND,

    /** Archive is unreadable (not a valid zip, out-of-bounds entries, or over the size cap). */
    INVALID_ARCHIVE,

    /** Selected file type is unsupported (e.g. importing a non-zip as an archive). */
    UNSUPPORTED_FILE,

    /** Disk write failed. */
    IO_FAILED
}

/** Failure record for one skill in a batch import. */
data class SkillImportFailure(val name: String, val error: SkillImportError)

/**
 * Result of one import: [imported] lists successfully written skill names; [failures] lists skipped skills and why;
 * a non-null [fatal] means the whole import failed (nothing imported, e.g. an invalid archive or no skills inside).
 */
data class SkillImportReport(
    val imported: List<String>,
    val failures: List<SkillImportFailure> = emptyList(),
    val fatal: SkillImportError? = null
)

/** One skill parsed from an archive: its name + the files under its skill directory (relative to the skill root, `/`-separated). */
internal data class ArchivedSkill(val name: String, val files: Map<String, ByteArray>)

/**
 * Core skill-import logic: writes one Markdown text or one zip archive into the skill root of the given scope.
 *
 * Depends only on [FileAccessProvider] + the skill root path + the set of existing names, one code path for local and remote (SSH), easy to unit test.
 * Name validation and conflict checks match in-app creation; conflicts never overwrite — the skill is skipped and recorded in the failure list.
 */
internal object SkillImporter {
    private const val TAG = "SkillImporter"
    private const val SKILL_FILE = "SKILL.md"
    private const val CLAUDE_FILE = "CLAUDE.md"

    /** Caps on total decompressed bytes and entry count, so a hostile archive cannot exhaust memory. */
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 2000

    /**
     * Imports a single skill from Markdown text: parses the frontmatter for name/description/tools, normalizes, and writes it as SKILL.md.
     * [fallbackName] is the fallback when the body lacks a name (e.g. the source file name).
     */
    fun importMarkdown(
        provider: FileAccessProvider,
        skillsRoot: String,
        existingNames: Set<String>,
        text: String,
        fallbackName: String
    ): SkillImportReport {
        val parsed = SkillParser.parseText(text, fallbackName)
        val name = parsed.name.trim()
        validate(name, existingNames)?.let { return SkillImportReport(emptyList(), fatal = it) }
        if (parsed.instructions.isBlank()) {
            return SkillImportReport(emptyList(), fatal = SkillImportError.EMPTY_CONTENT)
        }
        val content = SkillParser.serialize(name, parsed.description, parsed.requiredTools, parsed.instructions)
        return try {
            provider.writeFile("${rootFor(skillsRoot, name)}/$SKILL_FILE", content, overwrite = true)
            SkillImportReport(imported = listOf(name))
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to import skill: $name", e)
            SkillImportReport(emptyList(), fatal = SkillImportError.IO_FAILED)
        }
    }

    /** Imports skills from a zip input stream (one archive may contain several skill directories). */
    fun importArchive(
        provider: FileAccessProvider,
        skillsRoot: String,
        existingNames: Set<String>,
        input: InputStream,
        fallbackName: String
    ): SkillImportReport {
        val skills = when (val read = readArchive(input, fallbackName)) {
            is ArchiveRead.Failed -> return SkillImportReport(emptyList(), fatal = read.error)
            is ArchiveRead.Ok -> read.skills
        }

        val imported = mutableListOf<String>()
        val failures = mutableListOf<SkillImportFailure>()
        // Names already taken within this import batch, so two same-named skills in one archive cannot overwrite each other.
        val used = existingNames.toMutableSet()

        for (skill in skills) {
            val name = skill.name.trim()
            val error = validate(name, used) ?: if (skill.files.isEmpty()) SkillImportError.EMPTY_CONTENT else null
            if (error != null) {
                failures += SkillImportFailure(name, error)
                continue
            }
            try {
                skill.files.forEach { (relative, bytes) ->
                    provider.writeBytes("${rootFor(skillsRoot, name)}/$relative", bytes, overwrite = true)
                }
                used += name.lowercase()
                imported += name
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to import skill: $name", e)
                failures += SkillImportFailure(name, SkillImportError.IO_FAILED)
            }
        }
        return SkillImportReport(imported = imported, failures = failures)
    }

    private fun validate(name: String, existingNames: Set<String>): SkillImportError? = when {
        !SkillRepository.isValidName(name) -> SkillImportError.INVALID_NAME
        name.lowercase() in existingNames -> SkillImportError.NAME_CONFLICT
        else -> null
    }

    private fun rootFor(skillsRoot: String, name: String) = "${skillsRoot.trimEnd('/')}/$name"

    private sealed interface ArchiveRead {
        data class Ok(val skills: List<ArchivedSkill>) : ArchiveRead
        data class Failed(val error: SkillImportError) : ArchiveRead
    }

    /**
     * Reads the zip into memory and assigns each file to "the deepest skill directory that is its ancestor", avoiding parent/child duplication for nested skills.
     * Guards against zip-slip, caps entry count and total size; an invalid archive or one without skills returns [ArchiveRead.Failed].
     */
    private fun readArchive(input: InputStream, fallbackName: String): ArchiveRead {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    if (++count > MAX_ENTRIES) return ArchiveRead.Failed(SkillImportError.INVALID_ARCHIVE)
                    val path = normalize(entry.name)
                    if (path != null) {
                        val bytes = zip.readBytes()
                        total += bytes.size
                        if (total > MAX_TOTAL_BYTES) return ArchiveRead.Failed(SkillImportError.INVALID_ARCHIVE)
                        entries[path] = bytes
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to read archive", e)
            return ArchiveRead.Failed(SkillImportError.INVALID_ARCHIVE)
        }
        if (entries.isEmpty()) return ArchiveRead.Failed(SkillImportError.INVALID_ARCHIVE)

        // Skill directories: the directories of entries containing instruction files (relative paths, "" for the root).
        val skillDirs = entries.keys
            .filter { isInstructionFile(it.substringAfterLast('/')) }
            .map { it.substringBeforeLast('/', "") }
            .distinct()
        if (skillDirs.isEmpty()) return ArchiveRead.Failed(SkillImportError.NO_SKILL_FOUND)

        // Assignment: each file goes to its deepest ancestor skill directory (a root-level skill matches everything as fallback).
        val grouped = skillDirs.associateWith { mutableMapOf<String, ByteArray>() }
        entries.forEach { (key, bytes) ->
            val owner = skillDirs
                .filter { it.isEmpty() || key.startsWith("$it/") }
                .maxByOrNull { it.length } ?: return@forEach
            val relative = if (owner.isEmpty()) key else key.substring(owner.length + 1)
            grouped.getValue(owner)[relative] = bytes
        }

        val skills = skillDirs.map { dir ->
            val files = grouped.getValue(dir)
            val instructionText = files.entries
                .firstOrNull { isInstructionFile(it.key.substringAfterLast('/')) }
                ?.value?.toString(Charsets.UTF_8).orEmpty()
            val dirFallback = dir.substringAfterLast('/').ifBlank { fallbackName }
            ArchivedSkill(name = SkillParser.parseText(instructionText, dirFallback).name, files = files)
        }
        return ArchiveRead.Ok(skills)
    }

    /** Normalizes a zip entry path: backslashes to forward slashes, drop the leading `./`; rejects absolute paths and out-of-bounds entries containing `..`. */
    private fun normalize(raw: String): String? {
        val path = raw.replace('\\', '/').trimStart('/').removePrefix("./")
        val segments = path.split('/')
        if (segments.any { it == ".." }) return null
        val cleaned = segments.filter { it.isNotEmpty() && it != "." }
        return if (cleaned.isEmpty()) null else cleaned.joinToString("/")
    }

    private fun isInstructionFile(name: String): Boolean =
        name.equals(SKILL_FILE, ignoreCase = true) || name.equals(CLAUDE_FILE, ignoreCase = true)
}
