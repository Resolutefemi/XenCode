package com.aicode.feature.agent.domain.tool.github

import com.aicode.core.net.AppProxy
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.credentials.domain.repository.CredentialRepository
import com.aicode.feature.git.domain.GitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * GitHub REST API v3 helpers shared by [GitHubTool] and [GitHubWriteTool]:
 * remote URL parsing, field-summarizing (keeps tool output compact for the model),
 * and authenticated HTTP via the app-wide proxy chain.
 */
internal object GitHubApi {

    private const val TAG = "GitHubApi"
    const val GITHUB_HOST = "github.com"
    const val API_BASE = "https://api.github.com"
    const val PAGE_SIZE = 30

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse a GitHub remote URL into `(owner, repo)`.
     * Supports `https://github.com/owner/repo(.git)`, `git@github.com:owner/repo(.git)`,
     * `ssh://git@github.com/owner/repo(.git)` and `git://github.com/owner/repo(.git)`;
     * extra trailing segments (e.g. `/pull/1`) are ignored. Returns null for non-GitHub hosts
     * or URLs that do not yield two path segments.
     */
    fun parseRemoteUrl(url: String): Pair<String, String>? {
        val trimmed = url.trim().removeSuffix("\r")
        if (trimmed.isEmpty()) return null
        val scpLike = Regex("^([^@/]+@)?([^/:]+):(.+)$")
        val hostAndPath: Pair<String, String> = when {
            trimmed.contains("://") -> {
                val rest = trimmed.substringAfter("://")
                val host = rest.substringBefore('/')
                val path = rest.substringAfter('/', "").substringBefore('?').trimStart('/')
                host to path
            }
            scpLike.matches(trimmed) -> {
                val m = scpLike.matchEntire(trimmed)!!
                m.groupValues[2] to m.groupValues[3].trimStart('/')
            }
            else -> return null
        }
        val (hostRaw, path) = hostAndPath
        val host = hostRaw.substringAfter('@').lowercase().substringBefore(':')
        if (host != GITHUB_HOST && host != "www.$GITHUB_HOST") return null
        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return null
        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")
        if (owner.isBlank() || repo.isBlank()) return null
        return owner to repo
    }

    fun repoSlug(owner: String, repo: String) = "$owner/$repo"

    /** Authenticated request against api.github.com. Returns response code + body text. */
    suspend fun request(
        method: String,
        path: String,
        token: String,
        body: JsonObject? = null,
        accept: String = "application/vnd.github+json"
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("$API_BASE$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "aicode/1.0")
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
            "PATCH" -> builder.patch((body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
            "PUT" -> builder.put((body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
            else -> builder.method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
        }
        client.newCall(builder.build()).execute().use { resp ->
            resp.code to (resp.body?.string().orEmpty())
        }
    }

    /** Extract the human-readable `message` field from a GitHub error body, if present. */
    fun errorMessageBody(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    fun error(code: Int, body: String): ToolResult.Error {
        val detail = errorMessageBody(body)
        val message = when (code) {
            401 -> "GitHub rejected the token (HTTP 401). Check that the stored token is valid and has not expired."
            403 -> "GitHub refused the request (HTTP 403). The token may lack permission, or the rate limit was hit."
            404 -> "GitHub resource not found (HTTP 404). Check the repository name and that the token can access it."
            422 -> "GitHub validation failed (HTTP 422)" + (detail?.let { ": $it" } ?: ".")
            else -> "GitHub API error (HTTP $code)" + (detail?.let { ": $it" } ?: ".")
        }
        return ToolResult.Error(message, "GITHUB_HTTP_$code")
    }

    fun argString(args: Map<String, JsonElement>, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    fun argInt(args: Map<String, JsonElement>, key: String): Int? =
        argString(args, key)?.toIntOrNull() ?: (args[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    // ── Response summarizers: keep only model-relevant fields, dropping huge payloads ──

    fun summarizeIssue(o: JsonObject): JsonObject = buildJsonObject {
        put("number", o["number"] ?: JsonPrimitive(-1))
        put("title", o["title"] ?: JsonPrimitive(""))
        put("state", o["state"] ?: JsonPrimitive(""))
        put("is_pull_request", o.containsKey("pull_request"))
        put("body_excerpt", excerpt(o["body"]?.jsonPrimitive?.contentOrNull))
        put("user", o["user"]?.jsonObject?.get("login") ?: JsonPrimitive(""))
        put("labels", o["labels"]?.let { el ->
            buildJsonArray {
                (el as? kotlinx.serialization.json.JsonArray)?.forEach { label ->
                    (label as? JsonObject)?.get("name")?.let { add(it) }
                }
            }
        } ?: buildJsonArray {})
        put("comments", o["comments"] ?: JsonPrimitive(0))
        put("created_at", o["created_at"] ?: JsonPrimitive(""))
        put("updated_at", o["updated_at"] ?: JsonPrimitive(""))
        put("html_url", o["html_url"] ?: JsonPrimitive(""))
    }

    fun summarizePull(o: JsonObject): JsonObject = buildJsonObject {
        put("number", o["number"] ?: JsonPrimitive(-1))
        put("title", o["title"] ?: JsonPrimitive(""))
        put("state", o["state"] ?: JsonPrimitive(""))
        put("draft", o["draft"] ?: JsonPrimitive(false))
        put("merged", o["merged"] ?: JsonPrimitive(false))
        put("mergeable", o["mergeable"] ?: JsonPrimitive(""))
        put("body_excerpt", excerpt(o["body"]?.jsonPrimitive?.contentOrNull))
        put("user", o["user"]?.jsonObject?.get("login") ?: JsonPrimitive(""))
        val head = o["head"] as? JsonObject
        val base = o["base"] as? JsonObject
        put("head_ref", head?.get("ref") ?: JsonPrimitive(""))
        put("head_sha", head?.get("sha") ?: JsonPrimitive(""))
        put("base_ref", base?.get("ref") ?: JsonPrimitive(""))
        put("changed_files", o["changed_files"] ?: JsonPrimitive(0))
        put("created_at", o["created_at"] ?: JsonPrimitive(""))
        put("updated_at", o["updated_at"] ?: JsonPrimitive(""))
        put("html_url", o["html_url"] ?: JsonPrimitive(""))
    }

    fun summarizeComment(o: JsonObject): JsonObject = buildJsonObject {
        put("user", o["user"]?.jsonObject?.get("login") ?: JsonPrimitive(""))
        put("created_at", o["created_at"] ?: JsonPrimitive(""))
        put("body", o["body"] ?: JsonPrimitive(""))
    }

    fun summarizeCheckRun(o: JsonObject): JsonObject = buildJsonObject {
        put("name", o["name"] ?: JsonPrimitive(""))
        put("status", o["status"] ?: JsonPrimitive(""))
        put("conclusion", o["conclusion"] ?: JsonPrimitive(""))
        put("started_at", o["started_at"] ?: JsonPrimitive(""))
        put("completed_at", o["completed_at"] ?: JsonPrimitive(""))
        put("html_url", o["html_url"] ?: JsonPrimitive(""))
    }

    /** Trim long bodies to keep tool output small; full text stays one GET away when needed. */
    fun excerpt(text: String?, max: Int = 400): String {
        if (text.isNullOrBlank()) return ""
        return if (text.length <= max) text else text.take(max) + "…[truncated]"
    }

    fun parseObjectOrThrow(body: String): JsonObject =
        json.parseToJsonElement(body).jsonObject

    /**
     * Pick the target repository: explicit `repo` arg ("owner/repo" or a full remote URL),
     * otherwise the workspace's GitHub remote via `git config --get remote.origin.url`.
     */
    suspend fun resolveRepo(gitRepository: GitRepository, args: Map<String, JsonElement>): Result<Pair<String, String>> {
        val explicit = argString(args, "repo")
        if (explicit != null) {
            val parsed = parseRepoArg(explicit)
                ?: return Result.failure(IllegalArgumentException("repo must look like \"owner/repo\""))
            return Result.success(parsed)
        }
        val remoteUrl = runCatching { gitRepository.getRepoUrl() }.getOrNull().orEmpty()
        val parsed = parseRemoteUrl(remoteUrl)
            ?: return Result.failure(IllegalArgumentException(
                "The workspace remote is not a GitHub repository" + if (remoteUrl.isBlank()) " (no remote configured)" else ": $remoteUrl"
            ))
        return Result.success(parsed)
    }

    private fun parseRepoArg(explicit: String): Pair<String, String>? {
        // Accept bare "owner/repo" as well as full remote URLs.
        parseRemoteUrl(explicit)?.let { return it }
        val parts = explicit.trim('/').split('/')
        if (parts.size == 2 && parts.all { it.isNotBlank() }) return parts[0] to parts[1].removeSuffix(".git")
        return null
    }
}

/**
 * Read-only GitHub tool `github`: issues, pull requests (including unified diff),
 * and CI check runs. Auto-approved; works in every agent mode.
 *
 * The repository defaults to the workspace's GitHub remote (`git config --get remote.origin.url`);
 * pass `repo` explicitly as `"owner/repo"` to work with another repository.
 * Authentication reuses the GitHub token stored in the credentials manager for host github.com.
 */
class GitHubTool @Inject constructor(
    private val gitRepository: GitRepository,
    private val credentialRepository: CredentialRepository
) : AgentTool() {

    private companion object {
        const val TAG = "GitHubTool"
        val READ_ACTIONS = listOf(
            "list_issues", "get_issue", "list_issue_comments",
            "list_pulls", "get_pull", "get_pull_diff", "list_checks"
        )
    }

    override val name = "github"
    override val description =
        "Read GitHub data for a repository: list/get issues, list issue comments, list/get pull requests, " +
            "get a pull request's unified diff, and list CI check runs for a ref. Default repository is the " +
            "current workspace remote; override with repo=\"owner/repo\"."
    override val capabilities = setOf(ToolCapability.NETWORK_READ)
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "Read action: list_issues / get_issue / list_issue_comments / list_pulls / get_pull / get_pull_diff / list_checks",
            required = true,
            enum = READ_ACTIONS
        ),
        "repo" to ToolParameter(
            name = "repo",
            type = ParameterType.STRING,
            description = "Repository as \"owner/repo\". Optional: defaults to the current workspace's GitHub remote.",
            required = false
        ),
        "number" to ToolParameter(
            name = "number",
            type = ParameterType.INTEGER,
            description = "Issue or pull request number (required for get_issue / list_issue_comments / get_pull / get_pull_diff)",
            required = false
        ),
        "ref" to ToolParameter(
            name = "ref",
            type = ParameterType.STRING,
            description = "Branch, tag or commit SHA for list_checks. A pull request number also works: its head SHA is used.",
            required = false
        ),
        "state" to ToolParameter(
            name = "state",
            type = ParameterType.STRING,
            description = "List filter: open (default) / closed / all",
            required = false,
            enum = listOf("open", "closed", "all")
        ),
        "page" to ToolParameter(
            name = "page",
            type = ParameterType.INTEGER,
            description = "1-based page number for list actions; 30 items per page",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = GitHubApi.argString(args, "action")?.lowercase()
            ?: return ToolResult.Error("Missing required parameter: action", "MISSING_ACTION")
        if (action !in READ_ACTIONS) {
            return ToolResult.Error(
                "Unknown github action: $action. Valid: ${READ_ACTIONS.joinToString(", ")}",
                "UNKNOWN_ACTION"
            )
        }
        val repo = GitHubApi.resolveRepo(gitRepository, args).getOrElse {
            return ToolResult.Error(it.message ?: "Could not determine the GitHub repository", "MISSING_REPO")
        }
        val token = credentialRepository.findForHost(GitHubApi.GITHUB_HOST)?.token?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Error(
                "No GitHub token configured. Save a credential for host github.com (username + personal access token) in the credentials manager, then retry.",
                "NO_TOKEN"
            )
        val (owner, name) = repo
        val number = GitHubApi.argInt(args, "number")
        val page = (GitHubApi.argInt(args, "page") ?: 1).coerceAtLeast(1)
        val state = GitHubApi.argString(args, "state") ?: "open"

        return withContext(Dispatchers.IO) {
            try {
                when (action) {
                    "list_issues" -> {
                        val (code, body) = GitHubApi.request(
                            "GET",
                            "/repos/$owner/$name/issues?state=$state&page=$page&per_page=${GitHubApi.PAGE_SIZE}",
                            token
                        )
                        if (code !in 200..299) return@withContext GitHubApi.error(code, body)
                        val arr = Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonArray
                        ToolResult.Success(buildJsonArray { arr.forEach { add(GitHubApi.summarizeIssue(it.jsonObject)) } })
                    }
                    "get_issue" -> {
                        val n = number ?: return@withContext ToolResult.Error("Missing required parameter: number", "MISSING_PARAM")
                        val (code, body) = GitHubApi.request("GET", "/repos/$owner/$name/issues/$n", token)
                        if (code !in 200..299) return@withContext GitHubApi.error(code, body)
                        ToolResult.Success(GitHubApi.summarizeIssue(GitHubApi.parseObjectOrThrow(body)))
                    }
                    "list_issue_comments" -> {
                        val n = number ?: return@withContext ToolResult.Error("Missing required parameter: number", "MISSING_PARAM")
                        val (code, body) = GitHubApi.request(
                            "GET",
                            "/repos/$owner/$name/issues/$n/comments?page=$page&per_page=${GitHubApi.PAGE_SIZE}",
                            token
                        )
                        if (code !in 200..299) return@withContext GitHubApi.error(code, body)
                        val arr = Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonArray
                        ToolResult.Success(buildJsonArray { arr.forEach { add(GitHubApi.summarizeComment(it.jsonObject)) } })
                    }
                    "list_pulls" -> {
                        val (code, body) = GitHubApi.request(
                            "GET",
                            "/repos/$owner/$name/pulls?state=$state&page=$page&per_page=${GitHubApi.PAGE_SIZE}",
                            token
                        )
                        if (code !in 200..299) return@withContext GitHubApi.error(code, body)
                        val arr = Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonArray
                        ToolResult.Success(buildJsonArray { arr.forEach { add(GitHubApi.summarizePull(it.jsonObject)) } })
                    }
                    "get_pull" -> {
                        val n = number ?: return@withContext ToolResult.Error("Missing required parameter: number", "MISSING_PARAM")
                        val (code, body) = GitHubApi.request("GET", "/repos/$owner/$name/pulls/$n", token)
                        if (code !in 200..299) return@withContext GitHubApi.error(code, body)
                        ToolResult.Success(GitHubApi.summarizePull(GitHubApi.parseObjectOrThrow(body)))
                    }
                    "get_pull_diff" -> {
                        val n = number ?: return@withContext ToolResult.Error("Missing required parameter: number", "MISSING_PARAM")
                        val (code, body) = GitHubApi.request(
                            "GET",
                            "/repos/$owner/$name/pulls/$n",
                            token,
                            accept = "application/vnd.github.v3.diff"
                        )
                        if (code !in 200..299) return@withContext GitHubApi.error(code, body)
                        ToolResult.Success(JsonPrimitive(body))
                    }
                    "list_checks" -> {
                        val rawRef = GitHubApi.argString(args, "ref")
                            ?: return@withContext ToolResult.Error("Missing required parameter: ref", "MISSING_PARAM")
                        val sha = rawRef.toIntOrNull()?.let { prNumber ->
                            val (c, b) = GitHubApi.request("GET", "/repos/$owner/$name/pulls/$prNumber", token)
                            if (c !in 200..299) return@withContext GitHubApi.error(c, b)
                            GitHubApi.parseObjectOrThrow(b)["head"]?.jsonObject?.get("sha")?.jsonPrimitive?.contentOrNull
                                ?: return@withContext ToolResult.Error("Pull request $prNumber has no head SHA", "NO_HEAD_SHA")
                        } ?: rawRef
                        val (statusCode, statusBody) = GitHubApi.request("GET", "/repos/$owner/$name/commits/$sha/status", token)
                        val (checksCode, checksBody) = GitHubApi.request(
                            "GET",
                            "/repos/$owner/$name/commits/$sha/check-runs?per_page=${GitHubApi.PAGE_SIZE}",
                            token
                        )
                        if (statusCode !in 200..299) return@withContext GitHubApi.error(statusCode, statusBody)
                        val result = buildJsonObject {
                            put("ref", sha)
                            if (statusCode in 200..299) {
                                val st = GitHubApi.parseObjectOrThrow(statusBody)
                                put("combined_state", st["state"] ?: JsonPrimitive(""))
                            }
                            if (checksCode in 200..299) {
                                val cr = GitHubApi.parseObjectOrThrow(checksBody)
                                put("total_count", cr["total_count"] ?: JsonPrimitive(0))
                                put("check_runs", buildJsonArray {
                                    (cr["check_runs"] as? kotlinx.serialization.json.JsonArray)?.forEach {
                                        add(GitHubApi.summarizeCheckRun(it.jsonObject))
                                    }
                                })
                            } else {
                                put("total_count", 0)
                                put("check_runs", buildJsonArray {})
                            }
                        }
                        ToolResult.Success(result)
                    }
                    else -> ToolResult.Error("Unknown action: $action", "UNKNOWN_ACTION")
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "github $action failed", e)
                ToolResult.Error("GitHub request failed: ${e.message}", "GITHUB_REQUEST_FAILED")
            }
        }
    }
}

/**
 * Mutating GitHub tool `githubWrite`: create/update/comment issues, create pull requests,
 * submit PR reviews, merge PRs. Requires user confirmation (ASK) and is blocked in
 * Plan/Ask modes the same way as other write tools.
 */
class GitHubWriteTool @Inject constructor(
    private val gitRepository: GitRepository,
    private val credentialRepository: CredentialRepository
) : AgentTool() {

    private companion object {
        const val TAG = "GitHubWriteTool"
        val WRITE_ACTIONS = listOf(
            "create_issue", "update_issue", "comment_issue",
            "create_pull", "review_pull", "merge_pull"
        )
        val REVIEW_EVENTS = listOf("APPROVE", "REQUEST_CHANGES", "COMMENT")
        val MERGE_METHODS = listOf("merge", "squash", "rebase")
    }

    override val name = "githubWrite"
    override val description =
        "Write to GitHub: create issues, update issue state/title/body, comment on issues or pull requests, " +
            "create pull requests, submit pull request reviews (APPROVE / REQUEST_CHANGES / COMMENT), and merge pull requests. " +
            "Default repository is the current workspace remote; override with repo=\"owner/repo\". Requires a stored GitHub token."
    override val capabilities = setOf(ToolCapability.NETWORK_READ, ToolCapability.NETWORK_WRITE)
    override val permissionPolicy = ToolPermissionPolicy.ASK

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "Write action: create_issue / update_issue / comment_issue / create_pull / review_pull / merge_pull",
            required = true,
            enum = WRITE_ACTIONS
        ),
        "repo" to ToolParameter(
            name = "repo",
            type = ParameterType.STRING,
            description = "Repository as \"owner/repo\". Optional: defaults to the current workspace's GitHub remote.",
            required = false
        ),
        "number" to ToolParameter(
            name = "number",
            type = ParameterType.INTEGER,
            description = "Issue or pull request number (required for update_issue / comment_issue / review_pull / merge_pull)",
            required = false
        ),
        "title" to ToolParameter(name = "title", type = ParameterType.STRING, description = "Issue or pull request title (required for create_issue / create_pull; optional for update_issue)", required = false),
        "body" to ToolParameter(name = "body", type = ParameterType.STRING, description = "Markdown body text (create_issue / create_pull / comment_issue / review_pull / update_issue)", required = false),
        "state" to ToolParameter(name = "state", type = ParameterType.STRING, description = "Target state for update_issue: open / closed", required = false, enum = listOf("open", "closed")),
        "head" to ToolParameter(name = "head", type = ParameterType.STRING, description = "Source branch for create_pull (required)", required = false),
        "base" to ToolParameter(name = "base", type = ParameterType.STRING, description = "Target branch for create_pull (required)", required = false),
        "event" to ToolParameter(name = "event", type = ParameterType.STRING, description = "Review verdict for review_pull (required): APPROVE / REQUEST_CHANGES / COMMENT", required = false, enum = REVIEW_EVENTS),
        "merge_method" to ToolParameter(name = "merge_method", type = ParameterType.STRING, description = "Merge strategy for merge_pull: merge (default) / squash / rebase", required = false, enum = MERGE_METHODS)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = GitHubApi.argString(args, "action")?.lowercase()
            ?: return ToolResult.Error("Missing required parameter: action", "MISSING_ACTION")
        if (action !in WRITE_ACTIONS) {
            return ToolResult.Error(
                "Unknown githubWrite action: $action. Valid: ${WRITE_ACTIONS.joinToString(", ")}",
                "UNKNOWN_ACTION"
            )
        }
        val repo = GitHubApi.resolveRepo(gitRepository, args).getOrElse {
            return ToolResult.Error(it.message ?: "Could not determine the GitHub repository", "MISSING_REPO")
        }
        val token = credentialRepository.findForHost(GitHubApi.GITHUB_HOST)?.token?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Error(
                "No GitHub token configured. Save a credential for host github.com (username + personal access token) in the credentials manager, then retry.",
                "NO_TOKEN"
            )
        val (owner, name) = repo
        val number = GitHubApi.argInt(args, "number")

        return withContext(Dispatchers.IO) {
            try {
                when (action) {
                    "create_issue" -> {
                        val title = GitHubApi.argString(args, "title")
                            ?: return@withContext ToolResult.Error("Missing required parameter: title", "MISSING_PARAM")
                        val payload = buildJsonObject { put("title", title); put("body", GitHubApi.argString(args, "body") ?: "") }
                        val (code, respBody) = GitHubApi.request("POST", "/repos/$owner/$name/issues", token, payload)
                        if (code !in 200..299) return@withContext GitHubApi.error(code, respBody)
                        ToolResult.Success(summarizeCreated(GitHubApi.parseObjectOrThrow(respBody)))
                    }
                    "update_issue" -> {
                        val n = number ?: return@withContext ToolResult.Error("Missing required parameter: number", "MISSING_PARAM")
                        val payload = buildJsonObject {
                            GitHubApi.argString(args, "title")?.let { put("title", it) }
                            GitHubApi.argString(args, "body")?.let { put("body", it) }
                            GitHubApi.argString(args, "state")?.let { put("state", it) }
                        }
                        val (code, respBody) = GitHubApi.request("PATCH", "/repos/$owner/$name/issues/$n", token, payload)
                        if (code !in 200..299) return@withContext GitHubApi.error(code, respBody)
                        ToolResult.Success(GitHubApi.summarizeIssue(GitHubApi.parseObjectOrThrow(respBody)))
                    }
                    "comment_issue" -> {
                        val n = number ?: return@withContext ToolResult.Error("Missing required parameter: number", "MISSING_PARAM")
                        val text = GitHubApi.argString(args, "body")
                            ?: return@withContext ToolResult.Error("Missing required parameter: body", "MISSING_PARAM")
                        val payload = buildJsonObject { put("body", text) }
                        val (code, respBody) = GitHubApi.request("POST", "/repos/$owner/$name/issues/$n/comments", token, payload)
                        if (code !in 200..299) return@withContext GitHubApi.error(code, respBody)
                        ToolResult.Success(summarizeCreated(GitHubApi.parseObjectOrThrow(respBody)))
                    }
                    "create_pull" -> {
                        val title = GitHubApi.argString(args, "title")
                            ?: return@withContext ToolResult.Error("Missing required parameter: title", "MISSING_PARAM")
                        val head = GitHubApi.argString(args, "head")
                            ?: return@withContext ToolResult.Error("Missing required parameter: head", "MISSING_PARAM")
                        val base = GitHubApi.argString(args, "base")
                            ?: return@withContext ToolResult.Error("Missing required parameter: base", "MISSING_PARAM")
                        val payload = buildJsonObject {
                            put("title", title); put("head", head); put("base", base)
                            put("body", GitHubApi.argString(args, "body") ?: "")
                        }
                        val (code, respBody) = GitHubApi.request("POST", "/repos/$owner/$name/pulls", token, payload)
                        if (code !in 200..299) return@withContext GitHubApi.error(code, respBody)
                        ToolResult.Success(GitHubApi.summarizePull(GitHubApi.parseObjectOrThrow(respBody)))
                    }
                    "review_pull" -> {
                        val n = number ?: return@withContext ToolResult.Error("Missing required parameter: number", "MISSING_PARAM")
                        val event = (GitHubApi.argString(args, "event") ?: "").uppercase()
                        if (event !in REVIEW_EVENTS) {
                            return@withContext ToolResult.Error(
                                "Missing or invalid parameter: event (one of ${REVIEW_EVENTS.joinToString("/")})",
                                "MISSING_PARAM"
                            )
                        }
                        val bodyText = GitHubApi.argString(args, "body")
                        if (event != "APPROVE" && bodyText.isNullOrBlank()) {
                            return@withContext ToolResult.Error(
                                "REQUEST_CHANGES and COMMENT reviews require a body explaining the decision",
                                "MISSING_PARAM"
                            )
                        }
                        val payload = buildJsonObject {
                            put("event", event)
                            bodyText?.let { put("body", it) }
                        }
                        val (code, respBody) = GitHubApi.request("POST", "/repos/$owner/$name/pulls/$n/reviews", token, payload)
                        if (code !in 200..299) return@withContext GitHubApi.error(code, respBody)
                        val review = GitHubApi.parseObjectOrThrow(respBody)
                        ToolResult.Success(buildJsonObject {
                            put("pull_request", n)
                            put("state", review["state"] ?: JsonPrimitive(event))
                            put("html_url", review["html_url"] ?: JsonPrimitive(""))
                            put("submitted_at", review["submitted_at"] ?: JsonPrimitive(""))
                        })
                    }
                    "merge_pull" -> {
                        val n = number ?: return@withContext ToolResult.Error("Missing required parameter: number", "MISSING_PARAM")
                        val method = GitHubApi.argString(args, "merge_method") ?: "merge"
                        if (method !in MERGE_METHODS) {
                            return@withContext ToolResult.Error("Invalid merge_method: $method", "MISSING_PARAM")
                        }
                        val payload = buildJsonObject { put("merge_method", method) }
                        val (code, respBody) = GitHubApi.request("PUT", "/repos/$owner/$name/pulls/$n/merge", token, payload)
                        if (code !in 200..299) return@withContext GitHubApi.error(code, respBody)
                        ToolResult.Success(GitHubApi.parseObjectOrThrow(respBody))
                    }
                    else -> ToolResult.Error("Unknown action: $action", "UNKNOWN_ACTION")
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "githubWrite $action failed", e)
                ToolResult.Error("GitHub request failed: ${e.message}", "GITHUB_REQUEST_FAILED")
            }
        }
    }

    private fun summarizeCreated(o: JsonObject): JsonObject = buildJsonObject {
        put("number", o["number"] ?: o["id"] ?: JsonPrimitive(-1))
        put("html_url", o["html_url"] ?: JsonPrimitive(""))
        put("created_at", o["created_at"] ?: JsonPrimitive(""))
    }
}
