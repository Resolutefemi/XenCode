package com.xencode.core.util

/**
 * The list of languages the app supports.
 *
 * To add a new language, just append an entry to this list, create the corresponding
 * res/values-xx/strings.xml, and update res/xml/locales_config.xml to match — no other code
 * changes needed.
 *
 * @property tag BCP-47 language tag.
 * @property displayName The language's endonym (written in the language itself), shown in the
 * language picker.
 */
data class SupportedLanguage(
    val tag: String,
    val displayName: String
)

object LanguageRegistry {

    /** Placeholder for "follow system"; not a real language tag. */
    const val FOLLOW_SYSTEM = ""

    val languages: List<SupportedLanguage> = listOf(
        SupportedLanguage("en", "English")
    )

    /** Whether the tag is a real supported language (excludes [FOLLOW_SYSTEM]). */
    fun isSupported(tag: String): Boolean = languages.any { it.tag == tag }
}
