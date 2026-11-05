package com.xencode.feature.agent.domain.prompt

import java.io.File

/**
 * Fragment parsing rules for `prompts.custom/` (pure file-system logic, easy to unit test).
 *
 * The directory holds two kinds of fragments:
 * - Top-level `<two-digit>-<name>.md`: the number is the identity. A number matching a built-in static fragment is an override; otherwise it joins the static baseline as a new fragment, sorted by number.
 * - Other files (including the `agent/` subdirectory): overridden by exact same name, with no numeric identity.
 */
internal object PromptFragmentResolver {

    /** Presence of this file fully disables built-in prompts (main agent only). */
    const val DISABLE_BUILTIN_FILE = ".no-builtin"

    private val NUMBERED = Regex("""^(\d{2})-(.+)\.md$""")

    /** The numeric value of a top-level numbered fragment; null if the name lacks a two-digit prefix. */
    fun parseNumber(fileName: String): Int? =
        NUMBERED.matchEntire(fileName)?.groupValues?.get(1)?.toInt()

    /**
     * Top-level `<two-digit>-<name>.md` files, ascending by number.
     * When a number has multiple files, keep only the lexicographically first to avoid unstable ordering.
     */
    fun numberedFragments(dir: File): List<Pair<Int, File>> {
        val files = dir.listFiles() ?: return emptyList()
        val byNumber = LinkedHashMap<Int, File>()
        files.filter { it.isFile }
            .sortedBy { it.name }
            .forEach { file ->
                val number = parseNumber(file.name) ?: return@forEach
                byNumber.putIfAbsent(number, file)
            }
        return byNumber.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    /** Whether `.no-builtin` exists. */
    fun isBuiltinDisabled(dir: File): Boolean = File(dir, DISABLE_BUILTIN_FILE).isFile
}