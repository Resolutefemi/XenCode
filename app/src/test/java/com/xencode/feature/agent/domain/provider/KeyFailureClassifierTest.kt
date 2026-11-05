package com.xencode.feature.agent.domain.provider

import com.xencode.feature.settings.domain.model.DEFAULT_KEY_SWITCH_STATUS_CODES
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Failure classification for multi-key switching ([Throwable.isKeySwitchFailure]): the default status-code set, configurable overrides, and cause-chain traversal.
 */
class KeyFailureClassifierTest {

    private fun httpError(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "{}".toResponseBody(null)))

    @Test
    fun default_codes_trigger_switch() {
        DEFAULT_KEY_SWITCH_STATUS_CODES.forEach { code ->
            assertTrue("HTTP $code should trigger a switch", httpError(code).isKeySwitchFailure())
        }
    }

    @Test
    fun transient_codes_do_not_trigger_switch() {
        listOf(408, 500, 502, 503, 504).forEach { code ->
            assertFalse("HTTP $code should not trigger a switch", httpError(code).isKeySwitchFailure())
        }
    }

    @Test
    fun custom_codes_override_default() {
        val custom = setOf(400, 418)
        assertTrue(httpError(400).isKeySwitchFailure(custom))
        assertFalse("a custom code set without 401 should not switch", httpError(401).isKeySwitchFailure(custom))
    }

    @Test
    fun stream_codes_and_message_fallback() {
        assertTrue(StreamApiException("insufficient_quota", "m").isKeySwitchFailure())
        assertTrue(StreamApiException("rate_limit_exceeded", "m").isKeySwitchFailure())
        assertTrue(IllegalStateException("HTTP 401 from upstream").isKeySwitchFailure())
        assertFalse(IllegalStateException("HTTP 500 Internal Server Error").isKeySwitchFailure())
    }

    @Test
    fun cause_chain_is_followed() {
        val wrapped = IllegalStateException("wrapped", httpError(403))
        assertTrue(wrapped.isKeySwitchFailure())
    }
}
