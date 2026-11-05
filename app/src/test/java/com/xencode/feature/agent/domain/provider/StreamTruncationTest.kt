package com.xencode.feature.agent.domain.provider

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Degraded delivery when a streaming response loses the connection before the end marker arrives ([StreamTruncation]).
 *
 * Coverage:
 * - Salvageability depends only on non-blank body text (with only thinking/tool names, degrading is pointless; retry instead)
 * - Half-written JSON arguments must be detected and dropped (running a tool with broken arguments is riskier than losing the call)
 * - After degrading, stopReason must land within [AIResponse.TRUNCATION_STOP_REASONS] or the agent loop won't auto-continue
 * - A stream that terminated normally is not mislabeled as truncated
 */
class StreamTruncationTest {

    private fun event(json: String): JsonObject =
        JsonParser.parseString(json.replace('\'', '"')).asJsonObject

    @Test
    fun salvageableOnlyWhenTextIsNotBlank() {
        assertFalse("".isSalvageableAfterTruncation())
        assertFalse("   \n\t".isSalvageableAfterTruncation())
        assertTrue("half a sentence".isSalvageableAfterTruncation())
    }

    @Test
    fun completeJsonObjectIsDetected() {
        assertTrue(looksLikeCompleteJsonObject("{\"path\":\"a.txt\"}"))
        assertTrue(looksLikeCompleteJsonObject("{}"))
        // On a dropped stream the arguments almost always stop mid-way
        assertFalse(looksLikeCompleteJsonObject("{\"path\":\"a.tx"))
        assertFalse(looksLikeCompleteJsonObject("{\"path\":"))
        assertFalse(looksLikeCompleteJsonObject(""))
    }

    @Test
    fun responsesTruncationMarksLengthAndSalvagesText() {
        val acc = ResponsesStreamAccumulator()
        acc.accept(event("{'type':'response.output_text.delta','delta':'a segment already streamed out'}"))
        assertFalse(acc.terminated)

        acc.markNetworkTruncated()

        val response = acc.toResponse()
        assertEquals("a segment already streamed out", response.content)
        // stopReason must hit TRUNCATION_STOP_REASONS, otherwise the agent loop will not continue
        assertEquals(STREAM_TRUNCATED_STOP_REASON, response.stopReason)
        assertTrue(response.isTruncated)
    }

    @Test
    fun responsesAlreadyTerminatedIsNotMarkedTruncated() {
        val acc = ResponsesStreamAccumulator()
        acc.accept(event("{'type':'response.output_text.delta','delta':'a complete answer'}"))
        acc.accept(
            JsonObject().apply {
                addProperty("type", "response.completed")
                add("response", JsonObject().apply { addProperty("status", "completed") })
            }
        )
        assertTrue(acc.terminated)

        // Setting the flag after reaching a terminal state must be ignored: a normal end must not be mislabeled as truncation
        acc.markNetworkTruncated()

        val response = acc.toResponse()
        assertFalse(response.isTruncated)
    }

    @Test
    fun responsesTruncationDropsHalfWrittenToolArguments() {
        val acc = ResponsesStreamAccumulator()
        acc.accept(event("{'type':'response.output_text.delta','delta':'let me read a file first'}"))

        val item = JsonObject().apply {
            addProperty("type", "function_call")
            addProperty("id", "fc_1")
            addProperty("call_id", "c1")
            addProperty("name", "writeFile")
        }
        acc.accept(
            JsonObject().apply {
                addProperty("type", "response.output_item.added")
                addProperty("output_index", 0)
                add("item", item)
            }
        )
        // The connection dropped while the arguments were only half streamed
        acc.accept(
            JsonObject().apply {
                addProperty("type", "response.function_call_arguments.delta")
                addProperty("output_index", 0)
                addProperty("item_id", "fc_1")
                addProperty("delta", "{\"path\":\"a.tx")
            }
        )

        acc.markNetworkTruncated()

        val response = acc.toResponse()
        assertTrue(response.isTruncated)
        // Writing a file with broken arguments is far riskier than losing this call
        assertTrue("calls with half-written arguments must be dropped", response.toolCalls.isEmpty())
        assertEquals("let me read a file first", response.content)
    }

    @Test
    fun geminiInteractionsTruncationMarksLength() {
        val acc = GeminiInteractionsStreamAccumulator()
        acc.accept(
            event("{'event_type':'interaction.start','interaction':{'status':'in_progress'}}")
        )
        acc.accept(
            event(
                "{'event_type':'step.start','index':0,'step':" +
                    "{'type':'model_output','id':'s0'}}"
            )
        )
        acc.accept(
            event("{'event_type':'step.delta','index':0,'delta':'a segment already streamed out'}")
        )
        assertFalse(acc.terminated)

        acc.markTruncatedByNetwork()

        val response = acc.toResponse()
        assertTrue(response.isTruncated)
        assertEquals(STREAM_TRUNCATED_STOP_REASON, response.stopReason)
    }
}
