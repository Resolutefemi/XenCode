package com.xencode.feature.agent.data.remote.openai

data class ChatCompletionRequest(
    val model: String,
    val messages: List<OpenAIChatMessage>,
    /** When null, Gson skips the field: models with a fixed server-side temperature (kimi-k3, gpt-5 series, etc.) return 400 if any value is sent. */
    val temperature: Float? = null,
    val reasoning_effort: String? = null,
    val tools: List<OpenAIToolDefinition>? = null,
    val tool_choice: String? = null,
    val stream: Boolean = false,
    val stream_options: StreamOptions? = null,
    /** Cache shard routing key (requests in the same session are routed to the same cache shard). Supported only by third-party compatible services; the official OpenAI API rejects this field. */
    val prompt_cache_key: String? = null
)

data class OpenAIChatMessage(
    val role: String,
    val content: Any?,
    val name: String? = null,
    val tool_calls: List<OpenAIToolCall>? = null,
    val tool_call_id: String? = null,
    /** DeepSeek thinking mode requires the previous assistant message's reasoning_content to be sent back verbatim, otherwise a 400. */
    val reasoning_content: String? = null,
    /** Some third-party compatible services (e.g. mimo) use the top-level reasoning field instead of reasoning_content for thinking content. */
    val reasoning: String? = null,
    /** Image-generation response extension: some compatible services return an `images` array in the assistant message (this project's user relay station uses this format). */
    val images: List<OpenAIImagePart>? = null
)

/** A single image item in the Chat Completions image-generation extension (streaming `delta.images` and non-streaming `message.images` share the same shape). */
data class OpenAIImagePart(
    val type: String? = null,
    val image_url: OpenAIImageUrl? = null,
    val index: Int? = null
)

data class OpenAIImageUrl(val url: String? = null)

data class OpenAIToolDefinition(
    val type: String = "function",
    val function: OpenAIFunctionDefinition
)

data class OpenAIFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

data class OpenAIToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAIFunctionCall
)

data class OpenAIFunctionCall(
    val name: String,
    val arguments: String
)

data class StreamOptions(
    val include_usage: Boolean = true
)

data class ChatCompletionResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage?
)

data class Choice(
    val index: Int,
    val message: OpenAIChatMessage?,
    val delta: OpenAIChatMessage?, // Used for streaming
    val finish_reason: String?
)

data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int,
    val prompt_tokens_details: PromptTokensDetails? = null
)

/**
 * Chat Completions input token details: cached_tokens is the portion that hit the cache,
 * and cache_write_tokens is the portion written to the cache (only since GPT-5.6, billed above the input rate).
 */
data class PromptTokensDetails(
    val cached_tokens: Int? = null,
    val cache_write_tokens: Int? = null
)

/**
 * Images API (POST /images/generations) request body. Gson serializes field names as-is,
 * so field names must stay snake_case to match the OpenAI API.
 *
 * Whether each parameter is sent is decided by the caller based on model compatibility (the GPT image series does not
 * support [responseFormat] and always returns b64_json; [background]/[moderation]/[outputFormat] apply only to the
 * GPT image series; [style] applies only to dall-e-3); when null, Gson skips the field.
 */
data class ImageGenerationRequest(
    val model: String,
    val prompt: String,
    val n: Int? = null,
    val size: String? = null,
    val quality: String? = null,
    val response_format: String? = null,
    val output_format: String? = null,
    val background: String? = null,
    val moderation: String? = null,
    val style: String? = null
)

/** A single generated image from the Images API. */
data class ImageGenerationResult(
    val b64_json: String? = null,
    val url: String? = null,
    val revised_prompt: String? = null
)

/** Images API token usage (returned by the gpt-image series; the dall-e series has no such field). */
data class ImageGenerationUsage(
    val input_tokens: Int = 0,
    val output_tokens: Int = 0,
    val total_tokens: Int = 0,
    val input_tokens_details: ImageTokenDetails? = null,
    val output_tokens_details: ImageTokenDetails? = null
)

/** Images API token details: image tokens split from text tokens. */
data class ImageTokenDetails(
    val image_tokens: Int = 0,
    val text_tokens: Int = 0
)

/** Images API response body (deserialized by Gson; only the needed fields are taken). */
data class ImageGenerationResponse(
    val created: Long = 0,
    val data: List<ImageGenerationResult> = emptyList(),
    val usage: ImageGenerationUsage? = null
)
