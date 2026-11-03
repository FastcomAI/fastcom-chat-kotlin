package com.fastcom.chat

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val ChatJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** Any non-2xx response from the API. */
public class ChatApiException(
    public val status: Int,
    message: String,
    /** Seconds to wait before retrying, from `Retry-After` on a 429. */
    public val retryAfter: Int? = null,
    /** Set on a 429 from an app-wide cap: `newUsersPerHour`, `messagesPerHour` or `messagesPerDay`. */
    public val limit: String? = null,
) : IOException("HTTP $status: $message") {
    /** The server's error text, without the status prefix. */
    public val error: String = message

    internal companion object {
        @Serializable
        private class Body(val error: String? = null, val limit: String? = null)

        fun from(status: Int, retryAfter: String?, body: String): ChatApiException {
            // The stream route answers errors in plain text, the rest in `{ error }`.
            val parsed = runCatching { ChatJson.decodeFromString<Body>(body) }.getOrNull()
            val message = parsed?.error ?: body.ifBlank { "HTTP $status" }
            return ChatApiException(status, message, retryAfter?.trim()?.toIntOrNull(), parsed?.limit)
        }
    }
}

internal class HttpResult(val code: Int, val retryAfter: String?, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Runs the call without blocking the caller's thread, cancels it with the
 * coroutine, and reads the body on OkHttp's thread so a caller on Android's
 * main thread never does network I/O.
 */
internal suspend fun Call.awaitResult(): HttpResult = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { HttpResult(it.code, it.header("Retry-After"), it.body?.string().orEmpty()) }
            } catch (e: IOException) {
                cont.resumeWithException(e)
                return
            }
            cont.resume(result)
        }
    })
}

/** Caches the session token; concurrent callers wait for one refresh. */
internal class TokenCache(
    private val provider: suspend () -> SessionToken,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var token: String? = null
    /** Epoch ms after which the token is treated as stale; null = unknown. */
    private var refreshAt: Long? = null

    suspend fun get(forceRefresh: Boolean = false): String {
        mutex.withLock {
            val current = token
            val stale = refreshAt?.let { now() >= it } ?: false
            if (!forceRefresh && current != null && !stale) return current
            val result = provider()
            require(result.token.isNotEmpty()) { "Token provider returned an empty token" }
            token = result.token
            val expiresIn = result.expiresIn
            // Refresh a minute early (or halfway, for very short tokens) so a
            // request never leaves with a token that expires in flight.
            refreshAt = if (expiresIn != null && expiresIn > 0) {
                now() + expiresIn * 1000 - minOf(60_000L, expiresIn * 500)
            } else {
                null
            }
            return result.token
        }
    }

    suspend fun clear() {
        mutex.withLock {
            token = null
            refreshAt = null
        }
    }
}
