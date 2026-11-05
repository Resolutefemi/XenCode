package com.xencode.feature.onboarding.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.onboardingDataStore by preferencesDataStore(name = "onboarding_prefs")

/** Persisted status of the first-launch onboarding. */
enum class OnboardingStatus(val persisted: String) {
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    SKIPPED("skipped");

    companion object {
        fun fromPersisted(value: String?): OnboardingStatus? =
            entries.firstOrNull { it.persisted == value }
    }
}

/**
 * First-launch onboarding progress persistence: one key records the three states.
 *
 * Written on every step advance, so a killed/restarted process resumes at the current step; completed/skipped are written once, only on state transitions.
 * Styled after [ThemeSettingsRepository] (the same DataStore pattern).
 */
@Singleton
class OnboardingRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ONBOARDING_STATUS_KEY = stringPreferencesKey("onboarding_status")
    }

    /** Current onboarding status; null on first install (never written), treated as IN_PROGRESS by callers. */
    val statusFlow: Flow<OnboardingStatus?> =
        context.onboardingDataStore.data.map { OnboardingStatus.fromPersisted(it[ONBOARDING_STATUS_KEY]) }

    /** Current status snapshot, read once for the launch decision. */
    suspend fun status(): OnboardingStatus? = statusFlow.first()

    suspend fun markStatus(status: OnboardingStatus) {
        context.onboardingDataStore.edit { it[ONBOARDING_STATUS_KEY] = status.persisted }
    }

    /** Completes onboarding; called after a real completion event, written once only when not already completed. */
    suspend fun complete() {
        val current = status()
        if (current != OnboardingStatus.COMPLETED) markStatus(OnboardingStatus.COMPLETED)
    }

    /** Skips onboarding; called when the user taps skip or on a forced reset. */
    suspend fun skip() {
        val current = status()
        if (current != OnboardingStatus.SKIPPED) markStatus(OnboardingStatus.SKIPPED)
    }

    /** Resets to in-progress (used by the settings page's "rerun onboarding"). */
    suspend fun resetToInProgress() {
        val current = status()
        if (current != OnboardingStatus.IN_PROGRESS) markStatus(OnboardingStatus.IN_PROGRESS)
    }
}