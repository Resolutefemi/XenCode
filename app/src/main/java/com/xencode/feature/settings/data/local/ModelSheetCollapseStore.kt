package com.xencode.feature.settings.data.local

import android.content.Context

/**
 * Persists the expand/collapse state of the model picker sheet's "collapse by provider" groups: remembers which providers the user collapsed, kept across sheet reopens and restarts.
 *
 * Stores the collapsed set (empty by default = all expanded); ids left over after a provider is deleted have no side effects. Forced expansion during search is not written to this state.
 */
class ModelSheetCollapseStore(context: Context) {

    private val prefs = context.getSharedPreferences("model_sheet_collapse", Context.MODE_PRIVATE)

    fun collapsedProviderIds(): Set<String> =
        prefs.getStringSet(KEY, null)?.toSet() ?: emptySet()

    /** Writes a copy of the new set: SharedPreferences forbids reusing an already-stored instance. */
    fun save(collapsedProviderIds: Set<String>) {
        prefs.edit().putStringSet(KEY, HashSet(collapsedProviderIds)).apply()
    }

    private companion object {
        const val KEY = "collapsed_provider_ids"
    }
}