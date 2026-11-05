package com.xencode.feature.agent.domain.provider

import com.xencode.feature.agent.data.remote.gemini.InteractionStep
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Interactions streaming event parsing ([GeminiInteractionsStreamAccumulator]) and non-streaming steps parsing.
 *
 * Coverage: termination is decided by interaction status reaching a terminal state (the official API sends no `[DONE]`, and a tool
 * turn stopping at `requires_action` never sends `completed`), tool arguments are aggregated verbatim, thinking signatures go into the snapshot,
 * and usage's "output excludes thinking, must be summed" behavior.
 */
class GeminiInteractionsStreamAccumulatorTest {

    /** Event JSON is written with single quotes to avoid layered escaping; replaced with double quotes before parsing. */
    private fun event(json: String): JsonObject =
        JsonParser.parseString(json.replace('\'', '"')).asJsonObject

    private fun feed(acc: GeminiInteractionsStreamAccumulator, vararg events: String): List<InteractionsDelta> =
        events.mapNotNull { acc.accept(event(it)) }

    private fun snapshotOf(response: AIResponse) =
        JsonParser.parseString(response.thinkingBlocksJson!!).asJsonArray

    @Test
    fun text_deltas_accumulate_and_stream_out() {
        val acc = GeminiInteractionsStreamAccumulator()
        val deltas = feed(
            acc,
            "{'event_type':'step.start','index':1,'step':{'type':'model_output'}}",
            "{'event_type':'step.delta','index':1,'delta':{'type':'text','text':'AI '}}",
            "{'event_type':'step.delta','index':1,'delta':{'type':'text','text':'works'}}",
            "{'event_type':'step.stop','index':1}",
            "{'event_type':'interaction.completed','interaction':{'id':'i1','status':'completed'}}"
        )
        assertEquals(listOf("AI ", "works"), deltas.map { (it as InteractionsDelta.Text).text })
        assertTrue(acc.terminated)
        val response = acc.toResponse()
        assertEquals("AI works", response.content)
        assertEquals("completed", response.stopReason)
        // Pure-text turns need no snapshot, avoiding double-writing with the body
        assertNull(response.thinkingBlocksJson)
    }

    @Test
    fun thought_summary_streams_as_reasoning_and_signature_lands_in_snapshot() {
        val acc = GeminiInteractionsStreamAccumulator()
        val deltas = feed(
            acc,
            "{'event_type':'step.start','index':0,'step':{'type':'thought'}}",
            "{'event_type':'step.delta','index':0,'delta':{'type':'thought_summary','content':{'type':'text','text':'look at the file first'}}}",
            "{'event_type':'step.delta','index':0,'delta':{'type':'thought_signature','signature':'sig-1'}}",
            "{'event_type':'step.stop','index':0}",
            "{'event_type':'step.start','index':1,'step':{'type':'model_output'}}",
            "{'event_type':'step.delta','index':1,'delta':{'type':'text','text':'ok'}}",
            "{'event_type':'interaction.completed','interaction':{'status':'completed'}}"
        )
        assertEquals("look at the file first", (deltas[0] as InteractionsDelta.Reasoning).text)
        val response = acc.toResponse()
        assertEquals("look at the file first", response.reasoning)
        assertEquals("ok", response.content)
        val snapshot = snapshotOf(response)
        assertEquals(2, snapshot.size())
        assertEquals(InteractionStep.THOUGHT, snapshot[0].asJsonObject.get("type").asString)
        // The signature cannot be rebuilt and must go into the snapshot for replay
        assertEquals("sig-1", snapshot[0].asJsonObject.get("signature").asString)
        assertEquals(InteractionStep.MODEL_OUTPUT, snapshot[1].asJsonObject.get("type").asString)
    }

    @Test
    fun thought_delta_with_inline_text_is_also_accepted() {
        val acc = GeminiInteractionsStreamAccumulator()
        // The migration guide example puts the thinking text directly on delta.text
        val deltas = feed(
            acc,
            "{'event_type':'step.start','index':0,'step':{'type':'thought'}}",
            "{'event_type':'step.delta','index':0,'delta':{'type':'thought','text':'thinking'}}"
        )
        assertEquals("thinking", (deltas.single() as InteractionsDelta.Reasoning).text)
    }

    @Test
    fun function_call_name_is_declared_at_step_start() {
        val acc = GeminiInteractionsStreamAccumulator()

        // The name arrives at step.start: the UI uses it to swap "thinking" for a concrete scene early
        val declared = acc.accept(
            event("{'event_type':'step.start','index':1,'step':{'type':'function_call','id':'fc_1','name':'readFile'}}")
        )
        assertEquals("readFile", (declared as InteractionsDelta.ToolCallDeclared).name)

        // The same step is announced only once; argument deltas produce no declaration
        assertNull(
            acc.accept(event("{'event_type':'step.delta','index':1,'delta':{'type':'arguments_delta','arguments':'{}'}}"))
        )

        // The declaration must not interrupt the step's other parsing: the full arguments carried by step.start still land in the aggregated result
        val withArgs = GeminiInteractionsStreamAccumulator()
        withArgs.accept(
            event("{'event_type':'step.start','index':1,'step':{'type':'function_call','id':'fc_2','name':'readFile','arguments':{'path':'b.kt'}}}")
        )
        withArgs.accept(event("{'event_type':'interaction.requires_action','interaction':{'status':'requires_action'}}"))
        val call = withArgs.toResponse().toolCalls.single()
        assertEquals("readFile", call.name)
        assertEquals("b.kt", call.arguments["path"].toString().trim('"'))
    }

    @Test
    fun function_call_arguments_are_accumulated_from_deltas() {
        val acc = GeminiInteractionsStreamAccumulator()
        feed(
            acc,
            "{'event_type':'step.start','index':1,'step':{'type':'function_call','id':'fc_1','name':'readFile'}}",
            "{'event_type':'step.delta','index':1,'delta':{'type':'arguments_delta','arguments':'{\\'path\\':'}}",
            "{'event_type':'step.delta','index':1,'delta':{'type':'arguments_delta','arguments':'\\'a.kt\\'}'}}",
            "{'event_type':'step.stop','index':1,'status':'waiting'}",
            "{'event_type':'interaction.requires_action','interaction':{'status':'requires_action'}}"
        )
        val response = acc.toResponse()
        val call = response.toolCalls.single()
        assertEquals("fc_1", call.id)
        assertEquals("readFile", call.name)
        assertEquals("a.kt", call.arguments["path"].toString().trim('"'))
        assertEquals("requires_action", response.stopReason)
    }

    @Test
    fun requires_action_terminates_the_stream() {
        val acc = GeminiInteractionsStreamAccumulator()
        feed(acc, "{'event_type':'interaction.created','interaction':{'status':'in_progress'}}")
        assertFalse(acc.terminated)
        // A tool turn stops at requires_action and the server never sends completed; waiting only for completed would be misread as a dropped stream
        feed(acc, "{'event_type':'interaction.status_update','interaction_id':'i1','status':'requires_action'}")
        assertTrue(acc.terminated)
    }

    @Test
    fun status_written_only_in_the_event_name_is_still_recognized() {
        val acc = GeminiInteractionsStreamAccumulator()
        feed(acc, "{'event_type':'interaction.requires_action','interaction':{'id':'i1'}}")
        assertTrue(acc.terminated)
        assertEquals("requires_action", acc.toResponse().stopReason)
    }

    @Test
    fun migration_guide_style_type_field_is_accepted() {
        val acc = GeminiInteractionsStreamAccumulator()
        // The migration guide's REST example uses `type` instead of the reference docs' `event_type`
        val deltas = feed(
            acc,
            "{'type':'step.start','index':0,'step':{'type':'model_output'}}",
            "{'type':'step.delta','index':0,'delta':{'type':'text','text':'hi'}}",
            "{'type':'interaction.completed','interaction':{'status':'completed'}}"
        )
        assertEquals("hi", (deltas.single() as InteractionsDelta.Text).text)
        assertTrue(acc.terminated)
    }

    @Test
    fun usage_adds_thought_tokens_into_output() {
        val acc = GeminiInteractionsStreamAccumulator()
        feed(
            acc,
            "{'event_type':'interaction.completed','interaction':{'status':'completed','usage':" +
                "{'total_input_tokens':7,'total_output_tokens':20,'total_thought_tokens':22,'total_cached_tokens':3}}}"
        )
        val response = acc.toResponse()
        assertEquals(7, response.inputTokens)
        // total_output_tokens excludes thinking, and thinking is billed at output rates (official example total = 7+20+22 = 49)
        assertEquals(42, response.outputTokens)
        assertEquals(3, response.cachedInputTokens)
    }

    @Test
    fun incomplete_status_is_treated_as_truncation() {
        val acc = GeminiInteractionsStreamAccumulator()
        feed(
            acc,
            "{'event_type':'step.start','index':0,'step':{'type':'model_output'}}",
            "{'event_type':'step.delta','index':0,'delta':{'type':'text','text':'half a sentence'}}",
            "{'event_type':'interaction.completed','interaction':{'status':'incomplete'}}"
        )
        // Hitting the output limit is now signaled by status=incomplete instead of finishReason=MAX_TOKENS,
        // normalized to length so upper layers trigger continuation; otherwise the reply would be silently truncated
        val response = acc.toResponse()
        assertEquals("length", response.stopReason)
        assertTrue(response.isTruncated)
    }

    @Test
    fun failed_status_carries_error_message_for_the_user() {
        val acc = GeminiInteractionsStreamAccumulator()
        feed(
            acc,
            "{'event_type':'interaction.completed','interaction':{'status':'failed'," +
                "'errors':[{'code':'internal','message':'the backend glitched'}]}}"
        )
        val response = acc.toResponse()
        assertTrue(response.isAborted)
        // On failed the body is usually empty; ending silently would show the user a blank bubble
        assertEquals("the backend glitched", response.stopDetail)
    }

    @Test
    fun error_event_throws_for_retry_classification() {
        val acc = GeminiInteractionsStreamAccumulator()
        val error = runCatching {
            acc.accept(event("{'event_type':'error','error':{'code':'not_found','message':'not found'}}"))
        }.exceptionOrNull()
        assertTrue(error is StreamApiException)
        assertEquals("not_found", (error as StreamApiException).code)
    }

    @Test
    fun non_stream_steps_parse_text_thought_and_function_call() {
        val steps = JsonParser.parseString(
            """
            [{'type':'thought','signature':'sig-1','summary':[{'type':'text','text':'think a bit'}]},
             {'type':'model_output','content':[{'type':'text','text':'here you go'}]},
             {'type':'function_call','id':'fc_1','name':'readFile','arguments':{'path':'a.kt'}}]
            """.trimIndent().replace('\'', '"')
        ).asJsonArray

        val parsed = parseInteractionSteps(steps)
        assertEquals("here you go", parsed.text)
        assertEquals("think a bit", parsed.reasoning)
        val call = parsed.toolCalls.single()
        assertEquals("fc_1", call.id)
        assertEquals("readFile", call.name)
        assertEquals("a.kt", call.arguments["path"].toString().trim('"'))
        // With thought / function_call present, a snapshot must be kept for replay
        assertEquals(3, JsonParser.parseString(parsed.stepsSnapshotJson!!).asJsonArray.size())
    }

    @Test
    fun stream_falls_back_to_steps_carried_by_the_terminal_event() {
        val acc = GeminiInteractionsStreamAccumulator()
        // When mid-stream deltas are all lost, the steps carried by the terminal event serve as the fallback
        feed(
            acc,
            "{'event_type':'interaction.completed','interaction':{'status':'completed','steps':" +
                "[{'type':'model_output','content':[{'type':'text','text':'fallback body'}]}]}}"
        )
        assertEquals("fallback body", acc.toResponse().content)
    }

    @Test
    fun usage_riding_on_step_delta_metadata_is_absorbed() {
        val acc = GeminiInteractionsStreamAccumulator()
        feed(
            acc,
            "{'event_type':'step.delta','index':0,'delta':{'type':'text','text':'hi'}," +
                "'metadata':{'total_usage':{'total_input_tokens':5,'total_output_tokens':2}}}"
        )
        assertEquals(5, acc.toResponse().inputTokens)
    }

    @Test
    fun parse_steps_extracts_image_blocks_from_model_output() {
        val steps = JsonParser.parseString(
            """
            [{'type':'model_output','content':[
              {'type':'text','text':'done drawing'},
              {'type':'image','mime_type':'image/png','data':'AAAABB=='}]}]
            """.trimIndent().replace('\'', '"')
        ).asJsonArray
        val output = parseInteractionSteps(steps)
        assertEquals("done drawing", output.text)
        assertEquals(1, output.images.size)
        assertEquals("image/png", output.images[0].mimeType)
        assertEquals("AAAABB==", output.images[0].base64Data)
    }

    @Test
    fun stream_start_event_with_image_content_lands_in_response_images() {
        val acc = GeminiInteractionsStreamAccumulator()
        // In streaming, images arrive whole in the step.start payload, producing no stream deltas
        feed(
            acc,
            "{'event_type':'step.start','index':0,'step':{'type':'model_output','content':[" +
                "{'type':'text','text':'here is the image'},{'type':'image','mime_type':'image/png','data':'QUJD'}]}}",
            "{'event_type':'step.stop','index':0}",
            "{'event_type':'interaction.completed','interaction':{'status':'completed','steps':[]}}"
        )
        val response = acc.toResponse()
        assertEquals("here is the image", response.content)
        assertEquals(1, response.images.size)
        assertEquals("image/png", response.images[0].mimeType)
        assertEquals("QUJD", response.images[0].base64Data)
    }

    @Test
    fun terminal_event_steps_with_images_are_the_fallback() {
        val acc = GeminiInteractionsStreamAccumulator()
        // When step.start carries no image, the steps carried by the terminal event parse the image as a fallback
        feed(
            acc,
            "{'event_type':'interaction.completed','interaction':{'status':'completed','steps':" +
                "[{'type':'model_output','content':[{'type':'image','mime_type':'image/jpeg','data':'MTIz'}]}]}}"
        )
        val response = acc.toResponse()
        assertEquals(1, response.images.size)
        assertEquals("image/jpeg", response.images[0].mimeType)
        assertEquals("MTIz", response.images[0].base64Data)
    }
}
