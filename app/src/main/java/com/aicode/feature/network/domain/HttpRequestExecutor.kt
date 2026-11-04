package com.aicode.feature.network.domain

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

@Singleton
class HttpRequestExecutor @Inject constructor(
    private val httpClient: OkHttpClient
) {
    fun execute(
        method: HttpMethod,
        url: String,
        queryJson: String = DEFAULT_JSON_OBJECT,
        headersJson: String = DEFAULT_JSON_OBJECT,
        bodyJson: String = DEFAULT_JSON_OBJECT,
        authJson: String = DEFAULT_AUTH_JSON,
        settings: NetworkClientSettings = NetworkClientSettings()
    ): NetworkSendState {
        val requestId = UUID.randomUUID().toString().take(8)
        val timings = CallTimings()
        val timeout = settings.timeoutSeconds.toLong().coerceIn(
            MIN_TIMEOUT_SECONDS.toLong(),
            MAX_TIMEOUT_SECONDS.toLong()
        )
        val clientBuilder = httpClient.newBuilder()
            .connectTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .writeTimeout(timeout, TimeUnit.SECONDS)
            .followRedirects(settings.followRedirects)
            .followSslRedirects(settings.followRedirects)
            .eventListener(timings)
        if (!settings.sslVerify) {
            runCatching {
                val trustAll = TrustAllManager()
                val sslContext = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(trustAll), SecureRandom())
                }
                clientBuilder.sslSocketFactory(sslContext.socketFactory, trustAll)
                clientBuilder.hostnameVerifier { _, _ -> true }
            }
        }
        val client = clientBuilder.build()

        val resolvedUrl = mergeQueryIntoUrl(url, queryJson)
        val requestHeaders = mergeHeaders(jsonToHeaders(headersJson), authHeaders(authJson))
        val hasBody = methodHasBody(method)
        val bodyBytes = if (hasBody && !isBlankJson(bodyJson)) {
            bodyJson.toByteArray(Charsets.UTF_8)
        } else {
            ByteArray(0)
        }
        val userContentType = requestHeaders.firstOrNull {
            it.name.equals("Content-Type", ignoreCase = true)
        }?.value
        val requestBody = if (hasBody) {
            bodyBytes.toRequestBody(
                (userContentType ?: "application/json; charset=utf-8").toMediaTypeOrNull()
            )
        } else {
            null
        }
        val builder = Request.Builder()
            .url(resolvedUrl)
            .method(method.name, requestBody)
        requestHeaders.filter { header ->
            header.name.isNotBlank() && !(hasBody && header.name.equals("Content-Type", ignoreCase = true))
        }.forEach { header ->
            builder.addHeader(header.name, header.value)
        }
        val request = builder.build()

        val startedAt = System.nanoTime()
        return try {
            client.newCall(request).execute().use { response ->
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                val responseBytes = response.body?.bytes() ?: ByteArray(0)
                val bodyText = responseBytes.toString(Charsets.UTF_8)
                val headers = response.headers.map { NetworkHeader(it.first, it.second) }
                val exchange = NetworkExchange(
                    headers = headers,
                    body = bodyText,
                    metrics = timings.toMetrics(
                        requestId = requestId,
                        method = method.name,
                        url = resolvedUrl,
                        statusCode = response.code,
                        statusText = response.message,
                        protocol = response.protocol.toString(),
                        durationMs = durationMs,
                        requestBytes = request.body?.contentLength()?.coerceAtLeast(0L) ?: 0L,
                        responseBytes = responseBytes.size.toLong(),
                        contentType = response.header("Content-Type").orEmpty()
                    )
                )
                NetworkSendState.Success(exchange)
            }
        } catch (e: IOException) {
            val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            NetworkSendState.Failure(
                message = e.message ?: e.javaClass.simpleName,
                exchange = NetworkExchange(
                    metrics = timings.toMetrics(
                        requestId = requestId,
                        method = method.name,
                        url = resolvedUrl,
                        durationMs = durationMs
                    )
                )
            )
        } catch (e: IllegalArgumentException) {
            NetworkSendState.Failure(e.message ?: e.javaClass.simpleName)
        }
    }
}

private fun mergeHeaders(
    explicit: List<NetworkHeader>,
    auth: List<NetworkHeader>
): List<NetworkHeader> {
    if (auth.isEmpty()) return explicit
    val names = explicit.map { it.name.lowercase() }.toSet()
    return explicit + auth.filter { it.name.lowercase() !in names }
}

private class TrustAllManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private class CallTimings : EventListener() {
    private var dnsStartNanos = 0L
    private var connectStartNanos = 0L
    private var tlsStartNanos = 0L
    private var dnsMs = -1L
    private var connectMs = -1L
    private var tlsMs = -1L
    private var remoteAddress = ""

    override fun dnsStart(call: Call, domainName: String) {
        dnsStartNanos = System.nanoTime()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
        if (dnsStartNanos > 0L) {
            dnsMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - dnsStartNanos)
        }
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStartNanos = System.nanoTime()
        remoteAddress = inetSocketAddress.toString().removePrefix("/")
    }

    override fun secureConnectStart(call: Call) {
        tlsStartNanos = System.nanoTime()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        if (tlsStartNanos > 0L) {
            tlsMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tlsStartNanos)
        }
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
    ) {
        if (connectStartNanos > 0L) {
            connectMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectStartNanos)
        }
        remoteAddress = inetSocketAddress.toString().removePrefix("/")
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        val address = connection.route().socketAddress
        remoteAddress = address.toString().removePrefix("/")
    }

    fun toMetrics(
        requestId: String,
        method: String,
        url: String,
        statusCode: Int = 0,
        statusText: String = "",
        protocol: String = "",
        durationMs: Long,
        requestBytes: Long = 0,
        responseBytes: Long = 0,
        contentType: String = ""
    ): NetworkMetrics = NetworkMetrics(
        requestId = requestId,
        method = method,
        url = url,
        statusCode = statusCode,
        statusText = statusText,
        protocol = protocol,
        remoteAddress = remoteAddress,
        durationMs = durationMs,
        dnsMs = dnsMs,
        connectMs = connectMs,
        tlsMs = tlsMs,
        requestBytes = requestBytes,
        responseBytes = responseBytes,
        contentType = contentType
    )
}
