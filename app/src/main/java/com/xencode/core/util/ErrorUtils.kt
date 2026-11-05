package com.xencode.core.util

import java.io.IOException

/**
 * Converts an exception into user-meaningful hint text.
 *
 * Many exceptions (e.g. NPEs, cancelled requests) have a null [Throwable.message], and using
 * it directly would display "null". Here we fall back to the exception class name and walk
 * up the cause chain, trying to surface something that helps locate the problem.
 */
fun Throwable.toUserMessage(): String {
    val parts = mutableListOf<String>()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 4) {
        val msg = current.message?.trim().orEmpty()
        val label = if (msg.isNotEmpty()) msg else friendlyName(current)
        if (parts.none { it == label }) parts.add(label)
        current = current.cause
        depth++
    }
    return parts.joinToString(" · ").ifBlank { "Unknown error" }
}

/** Friendlier English copy for common exception types. */
private fun friendlyName(t: Throwable): String = when (t) {
    is java.net.UnknownHostException -> "Cannot reach server (DNS lookup failed; check the network or Base URL)"
    is java.net.SocketTimeoutException -> "Request timed out (slow network or the service did not respond)"
    is java.net.ConnectException -> "Connection refused (check the Base URL and port)"
    is javax.net.ssl.SSLException -> "SSL connection failed"
    is IOException -> "Network I/O failed: ${t.javaClass.simpleName}"
    else -> t.javaClass.simpleName
}
