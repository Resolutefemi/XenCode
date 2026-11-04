package com.aicode.core.util

import java.io.IOException

/**
 * 把异常转换成对用户有意义的提示文本。
 *
 * 很多异常（如 NPE、被取消的请求）的 [Throwable.message] 为 null，
 * 直接使用会显示成 "null"。这里回退到异常类名，并向上追溯 cause 链，
 * 尽量给出可定位问题的信息。
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
