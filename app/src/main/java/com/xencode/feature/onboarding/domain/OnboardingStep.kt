package com.xencode.feature.onboarding.domain

import androidx.annotation.StringRes
import com.xencode.R

/**
 * Full simulated walkthrough of the first-launch end-to-end onboarding:
 *
 * 1. [OPEN_SIDEBAR]: home focuses the top-left hamburger button, prompting to open the sidebar.
 * 2. [ENTER_SETTINGS]: with the sidebar open, focuses the bottom "Settings" item, prompting to enter system settings.
 * 3. [CONFIG_PROVIDER]: the settings page focuses the "AI providers" row, prompting to enter the provider list.
 * 4. [PROVIDER_ADD]: the provider list focuses the top-bar "+" add button, prompting to add a provider.
 * 5. [PROVIDER_CONFIG_INFO]: the provider edit page focuses the API Key / config info input area.
 * 6. [PROVIDER_FETCH_MODELS]: the provider's Models tab focuses the "fetch models" button.
 * 7. [SIMULATE_FETCH_DIALOG]: the mask simulates the fetch-models dialog, showing fetched models and guiding the user to add one.
 * 8. [OPEN_MODEL_PICKER]: back on the home page, focuses the model picker button, prompting to open the panel.
 * 9. [SIMULATE_CHOOSE_MODEL]: the mask simulates the home model picker panel, demonstrating model selection.
 * 10. [SEND_MESSAGE]: home focuses the bottom input box, prompting to type and send the first message to start a conversation.
 */
enum class OnboardingStep(
    val route: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descRes: Int
) {
    OPEN_SIDEBAR(
        route = "chat",
        titleRes = R.string.onboarding_open_sidebar_title,
        descRes = R.string.onboarding_open_sidebar_desc
    ),
    ENTER_SETTINGS(
        route = "chat",
        titleRes = R.string.onboarding_enter_settings_title,
        descRes = R.string.onboarding_enter_settings_desc
    ),
    CONFIG_PROVIDER(
        route = "settings",
        titleRes = R.string.onboarding_config_provider_title,
        descRes = R.string.onboarding_config_provider_desc
    ),
    PROVIDER_ADD(
        route = "settings",
        titleRes = R.string.onboarding_provider_add_title,
        descRes = R.string.onboarding_provider_add_desc
    ),
    PROVIDER_CONFIG_INFO(
        route = "settings",
        titleRes = R.string.onboarding_provider_config_info_title,
        descRes = R.string.onboarding_provider_config_info_desc
    ),
    PROVIDER_FETCH_MODELS(
        route = "settings",
        titleRes = R.string.onboarding_provider_fetch_models_title,
        descRes = R.string.onboarding_provider_fetch_models_desc
    ),
    SIMULATE_FETCH_DIALOG(
        route = "settings",
        titleRes = R.string.onboarding_provider_select_fetched_model_title,
        descRes = R.string.onboarding_provider_select_fetched_model_desc
    ),
    OPEN_MODEL_PICKER(
        route = "chat",
        titleRes = R.string.onboarding_open_model_picker_title,
        descRes = R.string.onboarding_open_model_picker_desc
    ),
    SIMULATE_CHOOSE_MODEL(
        route = "chat",
        titleRes = R.string.onboarding_choose_model_in_sheet_title,
        descRes = R.string.onboarding_choose_model_in_sheet_desc
    ),
    SEND_MESSAGE(
        route = "chat",
        titleRes = R.string.onboarding_send_message_title,
        descRes = R.string.onboarding_send_message_desc
    );

    /** 1-based ordinal of the current step. */
    val stepIndex: Int get() = ordinal + 1

    /** Whether this is the last step. */
    val isLastStep: Boolean get() = this == entries.last()

    companion object {
        val totalSteps: Int get() = entries.size
        val all: List<OnboardingStep> = entries
    }
}
