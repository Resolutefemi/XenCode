package com.aicode.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LanguageRegistry] 支持语言清单与 tag 判定。 */
class LanguageRegistryTest {

    @Test
    fun en_is_supported() {
        assertTrue(LanguageRegistry.isSupported("en"))
        assertFalse(LanguageRegistry.isSupported("zh"))
    }

    @Test
    fun follow_system_placeholder_is_not_supported() {
        assertFalse(LanguageRegistry.isSupported(LanguageRegistry.FOLLOW_SYSTEM))
        assertFalse(LanguageRegistry.isSupported(""))
    }

    @Test
    fun unsupported_tags_are_rejected() {
        assertFalse(LanguageRegistry.isSupported("fr"))
        assertFalse(LanguageRegistry.isSupported("ja"))
        assertFalse(LanguageRegistry.isSupported("ZH"))
        assertFalse(LanguageRegistry.isSupported("en-US"))
        assertFalse(LanguageRegistry.isSupported("zh-CN"))
    }

    @Test
    fun languages_contain_en() {
        val tags = LanguageRegistry.languages.map { it.tag }
        assertTrue(tags.contains("en"))
        assertFalse(tags.contains("zh"))
    }

    @Test
    fun display_names_are_native() {
        assertTrue(LanguageRegistry.languages.any { it.tag == "en" && it.displayName == "English" })
    }
}