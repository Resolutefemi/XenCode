package com.aicode.feature.settings.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Marketplace backend base URL (AiCodeProBackend, hosted on Render).
 * Local debugging: switch to http://10.0.2.2:8787 (emulator) or run
 * `adb reverse tcp:8787 tcp:8787` first and use http://localhost:8787.
 */
object MarketplaceConfig {
    const val BASE_URL = "https://aicodepro.onrender.com"
    const val CATEGORIES_PATH = "/api/marketplace/categories"
    const val SKILLS_PATH = "/api/marketplace/skills"
    const val PROMPTS_PATH = "/api/marketplace/prompts"
    const val SUBAGENTS_PATH = "/api/marketplace/subagents"
    const val DEFAULT_PAGE_SIZE = 20
}

@Serializable
data class MarketplaceCategory(
    val id: String,
    val name: String,
    val skillCount: Int = 0
)

@Serializable
data class MarketplaceSkillSummary(
    val id: String,
    val categoryId: String,
    val name: String,
    val description: String,
    val creator: String? = null,
    val publishedAt: String? = null,
    val version: String? = null,
    val scope: String? = null,
    val requiredTools: List<String> = emptyList()
)

/** Paginated envelope of the /skills endpoint. */
@Serializable
data class MarketplaceSkillPage(
    val items: List<MarketplaceSkillSummary> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = MarketplaceConfig.DEFAULT_PAGE_SIZE,
    val total: Int = 0,
    val totalPages: Int = 1
)

/** /skills/:id detail including the instruction markdown. */
@Serializable
data class MarketplaceSkillDetail(
    val id: String,
    val categoryId: String,
    val name: String,
    val description: String,
    val creator: String? = null,
    val publishedAt: String? = null,
    val version: String? = null,
    val scope: String? = null,
    val requiredTools: List<String> = emptyList(),
    val instructions: String
)

/**
 * Shared list summary for prompt templates and sub-agents (no categoryId).
 */
@Serializable
data class MarketplaceItemSummary(
    val id: String,
    val name: String,
    val description: String,
    val creator: String? = null,
    val publishedAt: String? = null,
    val version: String? = null
)

/** Shared paginated envelope for /prompts and /subagents. */
@Serializable
data class MarketplaceItemPage(
    val items: List<MarketplaceItemSummary> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = MarketplaceConfig.DEFAULT_PAGE_SIZE,
    val total: Int = 0,
    val totalPages: Int = 1
)

/** /prompts/:id detail: template title + body; the app stores it as an <NN>-<title>.md fragment. */
@Serializable
data class MarketplacePromptDetail(
    val id: String,
    val name: String,
    val description: String = "",
    val creator: String? = null,
    val publishedAt: String? = null,
    val version: String? = null,
    val title: String,
    val body: String
)

/**
 * /subagents/:id detail; fields map 1:1 to the app's agents/<name>.md frontmatter:
 * mode is one of build/plan/ask, reasoningEffort one of low/medium/high/xhigh,
 * inject one of base/mainRules/skills/memory/projectRules (or the single ["none"]).
 */
@Serializable
data class MarketplaceSubAgentDetail(
    val id: String,
    val name: String,
    val description: String = "",
    val creator: String? = null,
    val publishedAt: String? = null,
    val version: String? = null,
    val prompt: String,
    val mode: String? = null,
    val reasoningEffort: String? = null,
    val inject: List<String>? = null,
    val allowedTools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),
    val providerId: String? = null,
    val model: String? = null
)

/** Server error envelope: { error: { code, message } }. */
@Serializable
internal data class MarketplaceErrorEnvelope(val error: MarketplaceError? = null)

@Serializable
internal data class MarketplaceError(val code: String? = null, val message: String? = null)

/** Marketplace request failure; [code] carries the server code or an HTTP_ status when available. */
class MarketplaceException(message: String, val code: String? = null) : IOException(message)

@Serializable
internal data class MarketplaceCategoriesResponse(val categories: List<MarketplaceCategory> = emptyList())

/**
 * Read-only marketplace API: categories, paginated lists, details.
 * Same approach as [ModelApiService] - plain OkHttp + kotlinx.serialization,
 * reusing the shared OkHttpClient's connection pool with tighter timeouts
 * so a slow market never blocks the screen.
 */
@Singleton
class MarketplaceService @Inject constructor(
    private val client: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val marketClient by lazy {
        client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** All categories, each with its computed skillCount. */
    suspend fun categories(): Result<List<MarketplaceCategory>> = runCatching {
        val body = getBody(MarketplaceConfig.BASE_URL + MarketplaceConfig.CATEGORIES_PATH)
        json.decodeFromString<MarketplaceCategoriesResponse>(body).categories
    }

    /** Paginated skill list; pass null [category] for all categories. */
    suspend fun skills(
        category: String? = null,
        page: Int = 1,
        pageSize: Int = MarketplaceConfig.DEFAULT_PAGE_SIZE
    ): Result<MarketplaceSkillPage> = runCatching {
        val url = (MarketplaceConfig.BASE_URL + MarketplaceConfig.SKILLS_PATH).toHttpUrlOrNull()
            ?.newBuilder()
            ?.apply {
                category?.let { addQueryParameter("category", it) }
                addQueryParameter("page", page.toString())
                addQueryParameter("pageSize", pageSize.toString())
            }
            ?.toString()
            ?: (MarketplaceConfig.BASE_URL + MarketplaceConfig.SKILLS_PATH)
        json.decodeFromString<MarketplaceSkillPage>(getBody(url))
    }

    /** Single skill detail (including the instruction body). */
    suspend fun skill(id: String): Result<MarketplaceSkillDetail> = runCatching {
        val body = getBody(MarketplaceConfig.BASE_URL + MarketplaceConfig.SKILLS_PATH + "/" + id)
        json.decodeFromString<MarketplaceSkillDetail>(body)
    }

    /** Paginated prompt template list. */
    suspend fun prompts(
        page: Int = 1,
        pageSize: Int = MarketplaceConfig.DEFAULT_PAGE_SIZE
    ): Result<MarketplaceItemPage> = runCatching {
        json.decodeFromString<MarketplaceItemPage>(getBody(pagedUrl(MarketplaceConfig.PROMPTS_PATH, page, pageSize)))
    }

    /** Single prompt template detail (title + body). */
    suspend fun prompt(id: String): Result<MarketplacePromptDetail> = runCatching {
        json.decodeFromString<MarketplacePromptDetail>(
            getBody(MarketplaceConfig.BASE_URL + MarketplaceConfig.PROMPTS_PATH + "/" + id)
        )
    }

    /** Paginated sub-agent list. */
    suspend fun subAgents(
        page: Int = 1,
        pageSize: Int = MarketplaceConfig.DEFAULT_PAGE_SIZE
    ): Result<MarketplaceItemPage> = runCatching {
        json.decodeFromString<MarketplaceItemPage>(getBody(pagedUrl(MarketplaceConfig.SUBAGENTS_PATH, page, pageSize)))
    }

    /** Single sub-agent detail (prompt + behavior fields). */
    suspend fun subAgent(id: String): Result<MarketplaceSubAgentDetail> = runCatching {
        json.decodeFromString<MarketplaceSubAgentDetail>(
            getBody(MarketplaceConfig.BASE_URL + MarketplaceConfig.SUBAGENTS_PATH + "/" + id)
        )
    }

    private fun pagedUrl(path: String, page: Int, pageSize: Int): String {
        return (MarketplaceConfig.BASE_URL + path).toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("page", page.toString())
            ?.addQueryParameter("pageSize", pageSize.toString())
            ?.toString()
            ?: (MarketplaceConfig.BASE_URL + path)
    }

    /** GET and return the response body; parse the error envelope and throw [MarketplaceException] on non-2xx. */
    private suspend fun getBody(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        try {
            marketClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val serverError = runCatching {
                        json.decodeFromString<MarketplaceErrorEnvelope>(body).error
                    }.getOrNull()
                    throw MarketplaceException(
                        message = serverError?.message ?: "HTTP ${response.code}",
                        code = serverError?.code ?: "HTTP_${response.code}"
                    )
                }
                body
            }
        } catch (e: MarketplaceException) {
            throw e
        } catch (e: Exception) {
            throw MarketplaceException(message = e.message ?: "network error", code = CODE_NETWORK)
        }
    }

    companion object {
        const val CODE_NETWORK = "NETWORK"
    }
}
