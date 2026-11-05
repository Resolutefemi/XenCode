package com.xencode.feature.agent.data.remote.gemini

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url

interface GeminiApi {
    @POST
    suspend fun generateContent(
        @Url url: String,
        @Header("x-goog-api-key") apiKey: String,
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: Any
    ): com.google.gson.JsonObject

    @Streaming
    @POST
    fun streamGenerateContent(
        @Url url: String,
        @Header("x-goog-api-key") apiKey: String,
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: Any
    ): retrofit2.Call<ResponseBody>

    /** Non-streaming request to the Interactions API (`v1beta/interactions`). */
    @POST
    suspend fun createInteraction(
        @Url url: String,
        @Header("x-goog-api-key") apiKey: String,
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: Any
    ): com.google.gson.JsonObject

    /**
     * Streaming request to the Interactions API: **the same endpoint** as non-streaming; switching to SSE
     * relies on `stream: true` in the request body plus `?alt=sse`, with no separate `:streamGenerateContent` like generateContent.
     */
    @Streaming
    @POST
    fun streamInteraction(
        @Url url: String,
        @Header("x-goog-api-key") apiKey: String,
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: Any
    ): retrofit2.Call<ResponseBody>
}
