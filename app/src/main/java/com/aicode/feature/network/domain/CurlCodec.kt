package com.aicode.feature.network.domain

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ParsedCurl(
    val method: HttpMethod = HttpMethod.GET,
    val url: String = "",
    val queryJson: String = DEFAULT_JSON_OBJECT,
    val headersJson: String = DEFAULT_JSON_OBJECT,
    val bodyJson: String = DEFAULT_JSON_OBJECT,
    val authJson: String = DEFAULT_AUTH_JSON,
    val followRedirects: Boolean = true,
    val sslVerify: Boolean = true
)

sealed interface CurlParseResult {
    data class Success(val parsed: ParsedCurl) : CurlParseResult
    data class Failure(val message: String) : CurlParseResult
}

fun parseCurlCommand(raw: String): CurlParseResult {
    return try {
        parseCurlCommandInternal(raw)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (_: Exception) {
        CurlParseResult.Failure("invalid")
    }
}

private fun parseCurlCommandInternal(raw: String): CurlParseResult {
    val tokens = tokenizeCurl(raw)
    if (tokens.isEmpty()) return CurlParseResult.Failure("empty")
    val start = tokens.indexOfFirst { token ->
        val lower = token.lowercase()
        lower == "curl" || lower == "curl.exe" || lower.endsWith("/curl")
    }
    if (start < 0) return CurlParseResult.Failure("not_curl")

    var method: HttpMethod? = null
    var url = ""
    val headers = mutableListOf<NetworkHeader>()
    val dataParts = mutableListOf<String>()
    var userInfo: String? = null
    var followRedirects = false
    var sslVerify = true
    var dataAsQuery = false
    var i = start + 1

    while (i < tokens.size) {
        val token = tokens[i]
        if (token.isEmpty() || token == "--") {
            i++
            continue
        }
        if (!token.startsWith("-") && url.isBlank() && looksLikeUrl(token)) {
            url = token
            i++
            continue
        }
        if (!token.startsWith("-")) {
            if (url.isBlank()) url = token
            i++
            continue
        }
        val flag = token
        val value = tokens.getOrNull(i + 1)
        when {
            flag == "-X" || flag == "--request" -> {
                method = parseMethod(value)
                i += 2
            }
            flag.startsWith("-X") && flag.length > 2 -> {
                method = parseMethod(flag.substring(2))
                i++
            }
            flag == "-H" || flag == "--header" || flag == "--headers" -> {
                parseHeader(value)?.let { headers += it }
                i += 2
            }
            flag == "-d" || flag == "--data" || flag == "--data-raw" ||
                flag == "--data-binary" || flag == "--data-ascii" ||
                flag == "--data-urlencode" -> {
                if (!value.isNullOrBlank()) dataParts += decodeDataValue(flag, value)
                i += 2
            }
            flag == "-u" || flag == "--user" -> {
                userInfo = value
                i += 2
            }
            flag == "--url" -> {
                if (!value.isNullOrBlank()) url = value
                i += 2
            }
            flag == "-G" || flag == "--get" -> {
                dataAsQuery = true
                i++
            }
            flag == "-L" || flag == "--location" || flag == "--location-trusted" -> {
                followRedirects = true
                i++
            }
            flag == "-k" || flag == "--insecure" -> {
                sslVerify = false
                i++
            }
            flag == "-I" || flag == "--head" -> {
                method = HttpMethod.HEAD
                i++
            }
            flag == "-A" || flag == "--user-agent" -> {
                if (!value.isNullOrBlank()) headers += NetworkHeader("User-Agent", value)
                i += 2
            }
            flag == "-e" || flag == "--referer" -> {
                if (!value.isNullOrBlank()) headers += NetworkHeader("Referer", value)
                i += 2
            }
            flag == "-b" || flag == "--cookie" -> {
                if (!value.isNullOrBlank()) headers += NetworkHeader("Cookie", value)
                i += 2
            }
            flag.startsWith("--") && value != null && !value.startsWith("-") -> i += 2
            else -> i++
        }
    }

    if (url.isBlank()) return CurlParseResult.Failure("missing_url")
    val bodyJoined = dataParts.joinToString("&")
    val resolvedMethod = method ?: if (bodyJoined.isNotBlank() && !dataAsQuery) HttpMethod.POST else HttpMethod.GET
    val (strippedUrl, urlQuery) = splitUrlAndQuery(url)
    val dataQuery = if (dataAsQuery) formToHeaders(bodyJoined) else emptyList()
    val queryRows = urlQuery + dataQuery
    val bodyJson = if (!dataAsQuery && bodyJoined.isNotBlank()) {
        prettyPrintJson(bodyJoined)
    } else {
        DEFAULT_JSON_OBJECT
    }
    val authJson = userInfo?.let { basicAuthJson(it) } ?: DEFAULT_AUTH_JSON
    return CurlParseResult.Success(
        ParsedCurl(
            method = resolvedMethod,
            url = strippedUrl,
            queryJson = if (queryRows.isEmpty()) DEFAULT_JSON_OBJECT else headersToJson(queryRows),
            headersJson = if (headers.isEmpty()) DEFAULT_JSON_OBJECT else headersToJson(headers),
            bodyJson = bodyJson,
            authJson = authJson,
            followRedirects = followRedirects,
            sslVerify = sslVerify
        )
    )
}

fun exportCurlCommand(
    method: HttpMethod,
    url: String,
    queryJson: String,
    headersJson: String,
    bodyJson: String,
    authJson: String,
    settings: NetworkClientSettings
): String {
    return try {
        exportCurlCommandInternal(method, url, queryJson, headersJson, bodyJson, authJson, settings)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (_: Exception) {
        "curl --request ${method.name} --url ${shellQuote(url.trim())}\n"
    }
}

private fun exportCurlCommandInternal(
    method: HttpMethod,
    url: String,
    queryJson: String,
    headersJson: String,
    bodyJson: String,
    authJson: String,
    settings: NetworkClientSettings
): String {
    val resolvedUrl = runCatching { mergeQueryIntoUrl(normalizeRequestUrl(url), queryJson) }
        .getOrDefault(normalizeRequestUrl(url).ifBlank { url.trim() })
    val parts = mutableListOf("curl", "--request", method.name, "--url", shellQuote(resolvedUrl))
    if (settings.followRedirects) parts += "--location"
    if (!settings.sslVerify) parts += "--insecure"
    jsonToHeaders(headersJson).filter { it.name.isNotBlank() }.forEach { header ->
        parts += listOf("--header", shellQuote("${header.name}: ${header.value}"))
    }
    authHeaders(authJson).forEach { header ->
        parts += listOf("--header", shellQuote("${header.name}: ${header.value}"))
    }
    if (methodHasBody(method) && !isBlankJson(bodyJson)) {
        parts += listOf("--data-raw", shellQuote(bodyJson.trim()))
    }
    return buildString {
        parts.forEachIndexed { index, part ->
            if (index == 0) {
                append(part)
            } else if (part.startsWith("--") && index > 0) {
                append(" \\\n  ")
                append(part)
            } else {
                append(' ')
                append(part)
            }
        }
        append('\n')
    }
}

internal fun tokenizeCurl(raw: String): List<String> {
    val source = raw
        .replace("\u00a0", " ")
        .replace("\\\r\n", " ")
        .replace("\\\n", " ")
        .replace("\\\r", " ")
        .trim()
    if (source.isEmpty()) return emptyList()
    val tokens = ArrayList<String>()
    val current = StringBuilder()
    var i = 0
    var quote: Char? = null
    while (i < source.length) {
        val ch = source[i]
        when {
            quote != null -> {
                if (ch == '\\' && quote == '"' && i + 1 < source.length) {
                    current.append(unescapeDouble(source[i + 1]))
                    i += 2
                } else if (ch == quote) {
                    quote = null
                    i++
                } else {
                    current.append(ch)
                    i++
                }
            }
            ch == '\'' || ch == '"' -> {
                quote = ch
                i++
            }
            ch.isWhitespace() -> {
                if (current.isNotEmpty()) {
                    tokens += current.toString()
                    current.clear()
                }
                i++
            }
            else -> {
                current.append(ch)
                i++
            }
        }
    }
    if (current.isNotEmpty()) tokens += current.toString()
    return tokens
}

private fun unescapeDouble(ch: Char): Char = when (ch) {
    'n' -> '\n'
    'r' -> '\r'
    't' -> '\t'
    else -> ch
}

private fun parseMethod(raw: String?): HttpMethod {
    val name = raw?.trim()?.uppercase().orEmpty()
    return HttpMethod.entries.firstOrNull { it.name == name } ?: HttpMethod.GET
}

private fun parseHeader(raw: String?): NetworkHeader? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val idx = text.indexOf(':')
    if (idx <= 0) return NetworkHeader(text, "")
    return NetworkHeader(text.substring(0, idx).trim(), text.substring(idx + 1).trim())
}

private fun decodeDataValue(flag: String, value: String): String {
    if (flag != "--data-urlencode") return value
    val eq = value.indexOf('=')
    return if (eq >= 0) {
        val key = value.substring(0, eq)
        val encoded = java.net.URLEncoder.encode(value.substring(eq + 1), Charsets.UTF_8.name())
        "$key=$encoded"
    } else {
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}

private fun looksLikeUrl(token: String): Boolean {
    val lower = token.lowercase()
    return lower.startsWith("http://") ||
        lower.startsWith("https://") ||
        lower.startsWith("www.") ||
        token.contains("://")
}

private fun splitUrlAndQuery(raw: String): Pair<String, List<NetworkHeader>> {
    val httpUrl = raw.trim().toHttpUrlOrNull()
        ?: normalizeRequestUrl(raw).toHttpUrlOrNull()
    if (httpUrl == null) return raw.trim() to emptyList()
    val query = httpUrl.queryParameterNames.flatMap { name ->
        httpUrl.queryParameterValues(name).map { value ->
            NetworkHeader(name, value.orEmpty())
        }
    }
    val stripped = httpUrl.newBuilder().query(null).build().toString()
    return stripped to query
}

private fun formToHeaders(raw: String): List<NetworkHeader> {
    if (raw.isBlank()) return emptyList()
    return raw.split('&').mapNotNull { part ->
        if (part.isBlank()) return@mapNotNull null
        val idx = part.indexOf('=')
        if (idx < 0) NetworkHeader(urlDecode(part), "")
        else NetworkHeader(urlDecode(part.substring(0, idx)), urlDecode(part.substring(idx + 1)))
    }
}

private fun urlDecode(raw: String): String = try {
    java.net.URLDecoder.decode(raw, Charsets.UTF_8.name())
} catch (_: Exception) {
    raw
}

private fun basicAuthJson(userInfo: String): String {
    val idx = userInfo.indexOf(':')
    val user = if (idx >= 0) userInfo.substring(0, idx) else userInfo
    val password = if (idx >= 0) userInfo.substring(idx + 1) else ""
    return prettyJson.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        kotlinx.serialization.json.JsonObject(
            mapOf(
                "type" to kotlinx.serialization.json.JsonPrimitive("basic"),
                "username" to kotlinx.serialization.json.JsonPrimitive(user),
                "password" to kotlinx.serialization.json.JsonPrimitive(password)
            )
        )
    )
}

private fun shellQuote(value: String): String {
    if (value.isEmpty()) return "''"
    if (value.all { it.isLetterOrDigit() || it in "._-/:?&=,@+%#" }) return value
    return "'" + value.replace("'", "'\\''") + "'"
}
