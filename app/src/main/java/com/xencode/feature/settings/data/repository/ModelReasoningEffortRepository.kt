package com.xencode.feature.settings.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Memory of "default reasoning effort per model": key = "providerId:model", value = ReasoningEffort.name.
 * Written when the user switches the reasoning effort under a model; used to initialize the session tier when a new session binds that model,
 * achieving per-model tier memory (similar to opencode storing the variants selection state per model).
 */
@Singleton
class ModelReasoningEffortRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Reads the default tier name for a model; returns null if never remembered. */
    fun get(providerId: String, model: String): String? =
        prefs.getString(key(providerId, model), null)

    /** Remembers the default tier name for a model. */
    fun set(providerId: String, model: String, effortName: String) {
        prefs.edit().putString(key(providerId, model), effortName).apply()
    }

    private fun key(providerId: String, model: String) = "$providerId:$model"

    private companion object {
        const val PREFS_NAME = "model_reasoning_effort_prefs"
    }
}