package com.xencode.feature.agent.domain.tool.question

/**
 * Request object for suspending and waiting for the user's answer after the AI calls the ask_user_question tool.
 *
 * @param id Unique identifier of this call (matches ToolCall.id), used for pairing in resolve.
 * @param questions The 1-4 questions to ask the user.
 */
data class PendingUserQuestion(
    val id: String,
    val questions: List<QuestionItem>
)

/**
 * A single question.
 *
 * @param question Full question text, e.g. "Which library should we use for HTTP requests?"
 * @param header Short label (≤12 characters), shown as a Chip/Tag, e.g. "Library" or "Approach".
 * @param options 2-4 preset options (the UI automatically appends an "Other" option).
 * @param multiSelect Whether multiple selections are allowed. false = single-choice RadioButton, true = multi-choice Checkbox.
 */
data class QuestionItem(
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiSelect: Boolean = false
)

/**
 * A single option.
 *
 * @param label Option label (1-5 short words), e.g. "OkHttp (recommended)".
 * @param description Option description, explaining what choosing it means or its trade-offs.
 */
data class QuestionOption(
    val label: String,
    val description: String
)

/**
 * The user's complete answers to one ask_user_question call.
 *
 * @param answers One [SingleAnswer] per question. Empty list if the user tapped "Add more info".
 */
data class UserQuestionAnswer(
    val answers: List<SingleAnswer>
)

/**
 * The answer to a single question.
 *
 * @param question The original question text (so the AI can tell which question each answer addresses).
 * @param selected The labels of the options the user selected (single-select = 1 element, multi-select may have several).
 * @param customText If the user picked "Other" and entered custom text, it is stored here; otherwise null.
 */
data class SingleAnswer(
    val question: String,
    val selected: List<String>,
    val customText: String? = null
)
