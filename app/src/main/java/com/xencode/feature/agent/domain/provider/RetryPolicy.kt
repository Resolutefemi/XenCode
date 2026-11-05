package com.xencode.feature.agent.domain.provider

import com.xencode.core.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import retrofit2.HttpException
import retrofit2.await
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.pow
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Network request retry policy modeled on opencode: exponential backoff, transient failures only.
 */
private const val TAG = "RetryPolicy"

// opencode uses attempts = 3 (at most 2 retries) → raised to 6 retries to cope with unstable networks
const val MAX_NETWORK_RETRIES = 6

/**
 * Per streaming attempt, the wait cap from request start to first meaningful content, including response headers.
 *
 * OkHttp's readTimeout is already unlimited; before first content the app layer cancels as a fallback,
 * so this is relaxed to 5 minutes to accommodate slow starts and long-reasoning models.
 */
const val FIRST_BYTE_TIMEOUT_MS = 300_000L

class FirstContentGuard {
    @Volatile private var close: (() -> Unit)? = null
    @Volatile private var cancelCall: (() -> Unit)? = null
    private var received = false
    private var expired = false
    @Volatile private var closed = false
    internal var timer: Job? = null

    fun attach(close: () -> Unit) {
        this.close = close
        if (closed) closeBody()
    }

    suspend fun awaitBody(call: retrofit2.Call<okhttp3.ResponseBody>): okhttp3.ResponseBody {
        cancelCall = { call.cancel() }
        if (closed) call.cancel()
        return call.await()
    }

    fun receivedContent() {
        synchronized(this) {
            received = true
            timer?.cancel()
        }
    }

    internal fun expire(): Boolean = synchronized(this) {
        if (received || expired) false else {
            expired = true
            true
        }
    }

    fun closeBody() {
        closed = true
        runCatching { cancelCall?.invoke() }
        runCatching { close?.invoke() }
    }
}

@OptIn(InternalCoroutinesApi::class)
suspend fun withFirstContentTimeout(
    timeoutMs: Long,
    block: suspend (FirstContentGuard) -> Unit
) {
    val guard = FirstContentGuard()
    val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
    try {
        coroutineScope {
            val attemptJob = coroutineContext[Job]!!
            // A blocking readLine cannot be woken by coroutine cancellation; the underlying Call must be cancelled when entering cancelling.
            val handle = attemptJob.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
                if (it != null) guard.closeBody()
            }
            val timer = launch(start = CoroutineStart.LAZY) {
                if (timeoutMs <= 0) return@launch
                delay(timeoutMs)
                if (guard.expire()) {
                    timedOut.set(true)
                    attemptJob.cancel(CancellationException("First content timeout"))
                }
            }
            guard.timer = timer
            timer.start()
            try {
                block(guard)
            } finally {
                timer.cancel()
                handle.dispose()
            }
        }
    } catch (e: Throwable) {
        coroutineContext.ensureActive()
        if (timedOut.get()) throw SocketTimeoutException("First content timeout after ${timeoutMs}ms")
        throw e
    }
}

/**
 * "Gap between chunks" watchdog for streaming responses: if no chunk arrives within [timeoutMs] of the previous one, call [close],
 * making the blocked read throw an IOException for the retry machinery to handle.
 *
 * Call [touch] once per received chunk to restart the timer; call [cancel] when the stream ends (normally or not).
 * [timeoutMs] <= 0 means unlimited; both methods are then no-ops.
 */
class StreamIdleWatchdog(
    private val scope: CoroutineScope,
    private val timeoutMs: Long,
    private val close: () -> Unit
) {
    private var timer: Job? = null

    fun touch() {
        if (timeoutMs <= 0) return
        timer?.cancel()
        timer = scope.launch {
            delay(timeoutMs)
            runCatching { close() }
        }
    }

    fun cancel() {
        timer?.cancel()
        timer = null
    }
}

/** Creates a [StreamIdleWatchdog] under the current coroutine; with [timeoutMs] <= 0 the created instance does nothing. */
suspend fun launchStreamIdleWatchdog(timeoutMs: Long, close: () -> Unit): StreamIdleWatchdog =
    StreamIdleWatchdog(CoroutineScope(coroutineContext[Job]!!), timeoutMs, close)

private val TRANSIENT_MESSAGES = listOf(
    "load failed",
    "network connection was lost",
    "network request failed",
    "failed to fetch",
    "econnreset",
    "econnrefused",
    "etimedout",
    "socket hang up"
)

/**
 * Matches opencode's delay=500, factor=2, maxDelay=10000.
 *
 * No longer the default retry wait (replaced by the fixed [NETWORK_RETRY_DELAY_MILLIS]); kept for scenarios
 * needing long backoff (e.g. as a fallback when all keys are rate-limited).
 */
fun exponentialDelayMillis(retryIndex: Int): Long {
    val delay = 500L
    val factor = 2.0
    val maxDelay = 10000L
    val wait = (delay * factor.pow(retryIndex)).toLong()
    return min(wait, maxDelay)
}

/**
 * Retry interval for network-class failures (milliseconds): **fixed 3 seconds**.
 *
 * Mobile network jitter and transient 5xx/resets from relays usually recover within 1~2 seconds; exponential backoff (0.5→1→2→4→8→10 s)
 * and typical `Retry-After: 23` headers just make the user wait. A uniform 3-second retry with the default 6-retry cap
 * costs at most ~18 extra seconds per stall, instead of often half a minute or more.
 */
const val NETWORK_RETRY_DELAY_MILLIS = 3_000L

/**
 * An error event received in the SSE stream, carrying a code for retry decisions.
 *
 * Aligned with Codex CLI's error classification: cyber_policy / invalid_request / context_window_exceeded /
 * quota_exceeded / usage_not_included etc. are non-retryable; server_error / server_is_overloaded etc. are retryable by default.
 */
class StreamApiException(
    val code: String?,
    message: String,
    val retryAfterMillis: Long? = null
) : Exception(message.ifBlank { code ?: "stream error" })

// Aligned with Codex CLI is_retryable(): these codes are explicitly non-retryable.
// Rate-limit codes (rate_limit_*) are listed so they prefer multi-key auto-switching; with a single key and no candidate, callers retry with rate-limit backoff.
// Quota codes (quota_exceeded / insufficient_quota / usage_* etc.) are not transient rate limits — retrying does not help, so they stay non-retryable.
private val NON_RETRYABLE_STREAM_CODES = setOf(
    "cyber_policy",
    "invalid_request_error",
    "invalid_request",
    "context_window_exceeded",
    "quota_exceeded",
    "usage_not_included",
    "usage_limit_reached",
    "insufficient_quota",
    "rate_limit_exceeded",
    "rate_limit_error",
    "invalid_image_request",
    "response_too_large"
)

/**
 * Matches opencode's isTransientError, plus Android-specific network exception types.
 *
 * Beyond the classic IOException check, it is HTTP status aware:
 * - 408 (request timeout), 5xx (500/502/503/504 etc.) → retryable (transient server failure)
 * - 429 (rate limit) → not handled here, left to multi-key auto-switching; when no key can be switched to, callers retry with rate-limit backoff (see [isRateLimitError])
 * - other 4xx → no retry (client error, retrying is pointless)
 */
fun isRetriableNetworkError(t: Throwable): Boolean {
    if (t is CancellationException) return false

    if (t is StreamApiException) {
        return !NON_RETRYABLE_STREAM_CODES.contains(t.code)
    }

    // Native network exceptions
    if (t is SocketTimeoutException || t is InterruptedIOException ||
        t is java.net.UnknownHostException || t is java.net.ConnectException ||
        t is javax.net.ssl.SSLException || t is IOException) {
        return true
    }

    // HTTP status aware: 408/5xx count as transient and retryable; 429 belongs to multi-key switching
    if (t is HttpException) {
        val code = t.code()
        return code == 408 || code >= 500
    }

    val message = t.message?.lowercase() ?: t.toString().lowercase()
    return TRANSIENT_MESSAGES.any { message.contains(it) }
}

/** In-stream rate-limit error codes (aligned with each provider's SSE error.code). */
private val RATE_LIMIT_STREAM_CODES = setOf(
    "rate_limit_exceeded",
    "rate_limit_error",
    "too_many_requests"
)

/** Fallback rate-limit message texts: once an HttpException is wrapped into an IllegalStateException by enrich, only the message text can identify it. */
private val RATE_LIMIT_MESSAGES = listOf(
    "rate limit",
    "too many requests",
    "http 429"
)

/** Max depth when walking the cause chain, kept consistent with [isKeySwitchFailure]. */
private const val MAX_CAUSE_DEPTH = 5

/**
 * Whether a failure is a rate-limit error (HTTP 429 / in-stream rate_limit_* / server rate-limit text).
 *
 * 429 is handled by multi-key auto-switching, so [isRetriableNetworkError] returns false for it; but with no key
 * to switch to (single key) and nothing streamed yet, backoff retry is the right move — 429 is transient and often carries Retry-After.
 */
fun isRateLimitError(t: Throwable): Boolean {
    var current: Throwable? = t
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        when (current) {
            is HttpException -> if (current.code() == 429) return true
            is StreamApiException -> if (current.code?.lowercase() in RATE_LIMIT_STREAM_CODES) return true
        }
        current = current.cause
        depth++
    }
    val message = t.message?.lowercase() ?: return false
    return RATE_LIMIT_MESSAGES.any { message.contains(it) }
}

/** User-visible categories of retry errors, so the UI can show the specific cause (not just a vague "network hiccup"). */
enum class RetryErrorKind {
    /** HTTP 429 / server rate-limit errors. */
    RATE_LIMIT,
    /** HTTP 503 server overload (or streaming codes server_is_overloaded / overloaded). */
    SERVER_OVERLOADED,
    /** HTTP 5xx (other than 503) and other server errors. */
    SERVER_ERROR,
    /** Connection/read timeout (including drops triggered by the first-byte watchdog). */
    TIMEOUT,
    /** No service on the target port; connection refused. */
    CONNECTION_REFUSED,
    /** DNS resolution failed (domain missing or network unreachable). */
    DNS_FAILED,
    /** Connection reset by the peer/middlebox after being established (dropped stream, unexpected end of stream, etc.). */
    CONNECTION_RESET,
    /** TLS/SSL handshake failure. */
    SSL_ERROR,
    /** Other network-layer failures. */
    NETWORK,
    /** Other errors that cannot be classified. */
    UNKNOWN
}

/**
 * Error summary for one retry, passed through to the UI along with Retrying events.
 * [statusCode] is the HTTP status code (e.g. 429/500); null for non-HTTP errors.
 */
data class RetryErrorInfo(
    val kind: RetryErrorKind,
    val statusCode: Int? = null
)

/** Classifies the exception that triggered a retry into a UI-displayable error summary. */
fun Throwable.toRetryErrorInfo(): RetryErrorInfo = when {
    this is HttpException -> when {
        code() == 429 -> RetryErrorInfo(RetryErrorKind.RATE_LIMIT, code())
        code() == 503 -> RetryErrorInfo(RetryErrorKind.SERVER_OVERLOADED, code())
        code() >= 500 -> RetryErrorInfo(RetryErrorKind.SERVER_ERROR, code())
        else -> RetryErrorInfo(RetryErrorKind.UNKNOWN, code())
    }
    this is StreamApiException -> when (code) {
        "rate_limit_exceeded", "rate_limit_error", "insufficient_quota" -> RetryErrorInfo(RetryErrorKind.RATE_LIMIT)
        "server_is_overloaded", "overloaded" -> RetryErrorInfo(RetryErrorKind.SERVER_OVERLOADED)
        "server_error", "internal_error" -> RetryErrorInfo(RetryErrorKind.SERVER_ERROR)
        else -> RetryErrorInfo(RetryErrorKind.UNKNOWN)
    }
    this is SocketTimeoutException || this is InterruptedIOException -> RetryErrorInfo(RetryErrorKind.TIMEOUT)
    this is UnknownHostException -> RetryErrorInfo(RetryErrorKind.DNS_FAILED)
    this is ConnectException -> RetryErrorInfo(RetryErrorKind.CONNECTION_REFUSED)
    // SSLException / ConnectException / UnknownHostException are all IOException subclasses and must match before IOException
    this is SSLException -> RetryErrorInfo(RetryErrorKind.SSL_ERROR)
    this is IOException -> if (isConnectionReset(this)) {
        RetryErrorInfo(RetryErrorKind.CONNECTION_RESET)
    } else {
        RetryErrorInfo(RetryErrorKind.NETWORK)
    }
    else -> RetryErrorInfo(RetryErrorKind.UNKNOWN)
}

/** Common error messages for streams cut off by the peer/middlebox; a match maps to [RetryErrorKind.CONNECTION_RESET]. */
private val RESET_MESSAGES = listOf(
    "connection reset",
    "reset by peer",
    "broken pipe",
    "unexpected end of stream",
    "connection closed",
    "socket hang up",
    "econnreset",
    "stream closed",
    "eof"
)

private fun isConnectionReset(e: IOException): Boolean {
    val message = e.message?.lowercase() ?: return false
    return RESET_MESSAGES.any { message.contains(it) }
}

/**
 * Parses `Retry-After` from the [HttpException] response headers, returning the wait in milliseconds.
 *
 * `Retry-After` has two formats:
 * 1. Seconds (e.g. `"30"`) → converted directly to milliseconds
 * 2. HTTP date (e.g. `"Fri, 29 Jun 2026 10:00:00 GMT"`) → compute the delta from now
 *
 * Note: an in-stream SSE error event ([StreamApiException]) can carry its own [StreamApiException.retryAfterMillis],
 * which takes precedence over the HTTP header.
 *
 * Non-matching types or a missing `Retry-After` header → null.
 * Parse failures also return null (falling back to the fixed [NETWORK_RETRY_DELAY_MILLIS]).
 */
fun extractRetryAfterMillis(t: Throwable): Long? {
    // Server-required wait carried by an in-stream error event (no HTTP header to read in the SSE case)
    if (t is StreamApiException) {
        t.retryAfterMillis?.takeIf { it > 0 }?.let { return it }
    }
    if (t !is HttpException) return null
    val header = t.response()?.headers()?.get("Retry-After") ?: return null

    // Format 1: plain seconds
    header.toLongOrNull()?.let { seconds ->
        return seconds * 1000L
    }

    // Format 2: HTTP date (RFC 1123)
    return runCatching {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val retryTime = sdf.parse(header)?.time ?: return null
        val delay = retryTime - System.currentTimeMillis()
        if (delay > 0) delay else null
    }.getOrNull()
}

/**
 * Computes the retry wait: **fixed [NETWORK_RETRY_DELAY_MILLIS] (3 seconds)**.
 *
 * A server `Retry-After` is only honored when shorter than 3 seconds; long values like the relays' common
 * `retry in 23s` always retry at 3 seconds — waiting 23 seconds is a terrible mobile experience, and in most cases the server limit has long been lifted.
 *
 * @param retryIndex Reserved for future use, e.g. distinguishing first vs. subsequent retry intervals.
 */
fun retryDelayMillis(retryIndex: Int, error: Throwable): Long {
    val serverDelay = extractRetryAfterMillis(error)
    if (serverDelay != null && serverDelay > 0) {
        return min(serverDelay, NETWORK_RETRY_DELAY_MILLIS)
    }
    return NETWORK_RETRY_DELAY_MILLIS
}

/**
 * Retries [block] with exponential backoff (name kept for compatibility), for non-streaming requests.
 *
 * @param onKeyFailure Multi-key switch callback: invoked first on failures deemed "non-retryable", with
 *        (triggering failure, whether resending is allowed). Returning true means the caller switched keys and the retry count may be reset before resending;
 *        returning false means rate-limit failures (429 / rate_limit_*) back off and retry, while others rethrow the original exception.
 *        Network-class failures (408/5xx/timeout etc.) never trigger this callback.
 * @param onRetry Pre-retry callback taking (current retry count, max retry count); used to notify upper layers that a retry is in progress.
 *                Declared before [block] so the trailing lambda of `retryStaircase { ... }` still binds to [block].
 * @param maxRetries Max retry count (excluding the first request), passed by the caller from "Settings → Network"; 0 means no retry.
 */
suspend fun <T> retryStaircase(
    maxRetries: Int = MAX_NETWORK_RETRIES,
    onKeyFailure: (suspend (Throwable, Boolean) -> Boolean)? = null,
    onRetry: (suspend (attempt: Int, maxRetries: Int, error: RetryErrorInfo) -> Unit)? = null,
    block: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            coroutineContext.ensureActive()
            if (!isRetriableNetworkError(e)) {
                if (onKeyFailure?.invoke(e, true) == true) {
                    attempt = 0
                    continue
                }
                // Rate-limit backoff retry when no key can be switched to (single key): 429 is transient, retrying after a wait beats failing outright
                if (!isRateLimitError(e)) throw e
            }
            if (attempt >= maxRetries) throw e
            val wait = retryDelayMillis(attempt, e)
            FileLogger.w(TAG, "Network request failed, retry ${attempt + 1}/$maxRetries (waiting ${wait}ms): ${e.javaClass.simpleName} ${e.message}")
            onRetry?.invoke(attempt + 1, maxRetries, e.toRetryErrorInfo())
            attempt++
            if (wait > 0) delay(wait)
        }
    }
}

/**
 * Retry wrapper for streaming requests (name kept for compatibility).
 *
 * @param onKeyFailure Multi-key switch callback: invoked first on failures deemed "non-retryable", with
 *        (triggering failure, whether resending is allowed). Resending is only allowed when this attempt has not received any content yet;
 *        once content has streamed, a switch may still happen (so later requests use the new key) but nothing is resent, avoiding duplicated content for the user.
 *        Returning false means rate-limit failures with no content yet back off and retry, while others rethrow the original exception.
 * @param onRetry Pre-retry callback taking (current retry count, max retry count); used to notify upper layers that a retry is in progress.
 *                Called before the delay so the UI can show the retry state immediately. Declared suspend so
 *                callers can emit retry events through a Flow's emit().
 * @param attemptOnce One streaming attempt; calls onContent once content arrives, to decide whether resending is allowed when switching keys.
 * @param maxRetries Max retry count (excluding the first request), passed by the caller from "Settings → Network"; 0 means no retry.
 */
suspend fun streamWithStaircaseRetry(
    maxRetries: Int = MAX_NETWORK_RETRIES,
    onKeyFailure: (suspend (Throwable, Boolean) -> Boolean)? = null,
    attemptOnce: suspend (onContent: () -> Unit) -> Unit,
    onRetry: (suspend (attempt: Int, maxRetries: Int, error: RetryErrorInfo) -> Unit)? = null
) {
    var attempt = 0
    while (true) {
        var receivedContent = false
        try {
            attemptOnce { receivedContent = true }
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            coroutineContext.ensureActive()
            if (!isRetriableNetworkError(e)) {
                if (onKeyFailure?.invoke(e, !receivedContent) == true) {
                    attempt = 0
                    continue
                }
                // Rate-limited with nothing streamed yet and no key to switch to (single key) → backoff retry; once content has streamed, never resend (existing behavior)
                if (!(isRateLimitError(e) && !receivedContent)) throw e
            }
            if (attempt >= maxRetries) throw e
            val wait = retryDelayMillis(attempt, e)
            FileLogger.w(TAG, "Streaming request failed, retry ${attempt + 1}/$maxRetries (waiting ${wait}ms): ${e.javaClass.simpleName} ${e.message}")
            onRetry?.invoke(attempt + 1, maxRetries, e.toRetryErrorInfo())
            attempt++
            if (wait > 0) delay(wait)
        }
    }
}
