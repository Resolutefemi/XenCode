package com.xencode.feature.agent.domain.tool.question

import com.xencode.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AskUserQuestionToolTest {

    private lateinit var manager: AskUserQuestionManager
    private lateinit var tool: AskUserQuestionTool

    @Before
    fun setUp() {
        manager = AskUserQuestionManager()
        tool = AskUserQuestionTool(manager)
    }

    @Test
    fun execute_multiSelectWithCustomText_retainsBothSelectedAndCustom() = runTest {
        val args = mapOf(
            "questions" to buildJsonArray {
                add(
                    buildJsonObject {
                        put("question", "Which dependencies should be included?")
                        put("header", "Dependencies")
                        put("multiSelect", true)
                        put(
                            "options",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("label", "Ktor")
                                    put("description", "HTTP client")
                                })
                                add(buildJsonObject {
                                    put("label", "Coroutines")
                                    put("description", "coroutines support")
                                })
                            }
                        )
                    }
                )
            }
        )

        val deferredResult = async { tool.execute(args) }

        val pending = manager.pendingQuestion.filterNotNull().first()
        assertEquals(1, pending.questions.size)

        manager.resolve(
            pending.id,
            UserQuestionAnswer(
                listOf(
                    SingleAnswer(
                        question = "Which dependencies should be included?",
                        selected = listOf("Ktor", "Coroutines"),
                        customText = "Serialization"
                    )
                )
            )
        )

        val result = deferredResult.await()
        assertTrue(result is ToolResult.Success)
        val text = (result as ToolResult.Success).data.jsonPrimitive.content
        assertEquals("\"Which dependencies should be included?\" = Ktor, Coroutines, Other: Serialization", text)
    }

    @Test
    fun execute_singleSelectWithPresetOption() = runTest {
        val args = mapOf(
            "questions" to buildJsonArray {
                add(
                    buildJsonObject {
                        put("question", "Choose a plan?")
                        put("header", "Plan")
                        put(
                            "options",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("label", "Plan A")
                                    put("description", "option A")
                                })
                                add(buildJsonObject {
                                    put("label", "Plan B")
                                    put("description", "option B")
                                })
                            }
                        )
                    }
                )
            }
        )

        val deferredResult = async { tool.execute(args) }
        val pending = manager.pendingQuestion.filterNotNull().first()

        manager.resolve(
            pending.id,
            UserQuestionAnswer(
                listOf(
                    SingleAnswer(
                        question = "Choose a plan?",
                        selected = listOf("Plan A"),
                        customText = null
                    )
                )
            )
        )

        val result = deferredResult.await()
        assertTrue(result is ToolResult.Success)
        val text = (result as ToolResult.Success).data.jsonPrimitive.content
        assertEquals("\"Choose a plan?\" = Plan A", text)
    }

    @Test
    fun execute_singleSelectWithOnlyCustomText() = runTest {
        val args = mapOf(
            "questions" to buildJsonArray {
                add(
                    buildJsonObject {
                        put("question", "Choose a plan?")
                        put("header", "Plan")
                        put(
                            "options",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("label", "Plan A")
                                    put("description", "option A")
                                })
                                add(buildJsonObject {
                                    put("label", "Plan B")
                                    put("description", "option B")
                                })
                            }
                        )
                    }
                )
            }
        )

        val deferredResult = async { tool.execute(args) }
        val pending = manager.pendingQuestion.filterNotNull().first()

        manager.resolve(
            pending.id,
            UserQuestionAnswer(
                listOf(
                    SingleAnswer(
                        question = "Choose a plan?",
                        selected = emptyList(),
                        customText = "custom plan C"
                    )
                )
            )
        )

        val result = deferredResult.await()
        assertTrue(result is ToolResult.Success)
        val text = (result as ToolResult.Success).data.jsonPrimitive.content
        assertEquals("\"Choose a plan?\" = Other: custom plan C", text)
    }

    @Test
    fun execute_userSkipped_returnsSupplementNotice() = runTest {
        val args = mapOf(
            "questions" to buildJsonArray {
                add(
                    buildJsonObject {
                        put("question", "Choose a plan?")
                        put("header", "Plan")
                        put(
                            "options",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("label", "Plan A")
                                    put("description", "option A")
                                })
                                add(buildJsonObject {
                                    put("label", "Plan B")
                                    put("description", "option B")
                                })
                            }
                        )
                    }
                )
            }
        )

        val deferredResult = async { tool.execute(args) }
        val pending = manager.pendingQuestion.filterNotNull().first()

        manager.resolve(pending.id, UserQuestionAnswer(emptyList()))

        val result = deferredResult.await()
        assertTrue(result is ToolResult.Success)
        val text = (result as ToolResult.Success).data.jsonPrimitive.content
        assertEquals("The user did not pick a preset option and will add more detail. Continue from their follow-up.", text)
    }
}
