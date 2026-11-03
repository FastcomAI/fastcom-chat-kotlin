package com.fastcom.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The end-user side of the Mobile Chat API: send messages, receive replies.
 *
 * ```kotlin
 * val chat = ChatClient("https://your-domain.com/app") { backend.fetchChatToken() }
 * chat.sendMessage("Where is my order?")
 * chat.events().collect { event -> ... }
 * ```
 *
 * @param baseUrl Your deployment's base URL, e.g. `https://your-domain.com/app`.
 * @param tokenProvider Recommended. Fetches a session token from your backend,
 *   which mints it with the secret key for the signed-in user.
 */
public class ChatClient(
    baseUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
    tokenProvider: suspend () -> SessionToken,
) {
    private val base: String = baseUrl.trimEnd('/')
    private val tokens = TokenCache(tokenProvider)

    // OkHttp silently resends a request when a pooled connection drops after
    // sending it. For a POST that means a duplicate message (and a second bot
    // run), so POSTs fail instead and the app decides. Shares http's pool.
    private val postHttp: OkHttpClient = http.newBuilder().retryOnConnectionFailure(false).build()

    public companion object {
        /**
         * Low-trust alternative for apps with no backend: mints tokens with the
         * publishable key. Must be enabled on the app card, and these sessions
         * cannot upload media.
         */
        @JvmStatic
        @JvmOverloads
        public fun withPublishableKey(
            baseUrl: String,
            publishableKey: String,
            endUserId: String,
            endUserName: String? = null,
            http: OkHttpClient = OkHttpClient(),
        ): ChatClient {
            // The server would happily mint a full-trust token from a secret
            // key, so this mistake would work and ship the secret key inside the
            // app. The message never echoes the key.
            require(!publishableKey.startsWith("sk_")) {
                "publishableKey is a secret key (sk_...). Never put a secret key in an app: " +
                    "mint tokens on your backend and use the tokenProvider constructor instead."
            }
            require(publishableKey.startsWith("pk_")) { "publishableKey must start with pk_" }
            val tokenUrl = endpoint(baseUrl.trimEnd('/'), "token")
            return ChatClient(baseUrl, http) {
                val body = buildJsonObject {
                    put("endUserId", endUserId)
                    if (endUserName != null) put("endUserName", endUserName)
                }.toString().toRequestBody(JSON)
                val request = Request.Builder()
                    .url(tokenUrl)
                    .header("Authorization", "Bearer $publishableKey")
                    .post(body)
                    .build()
                decode<SessionToken>(http.newCall(request).awaitResult())
            }
        }

        private val JSON = "application/json".toMediaType()

        private fun endpoint(base: String, path: String): HttpUrl = "$base/api/v1/chat/$path".toHttpUrl()

        private inline fun <reified T> decode(result: HttpResult): T {
            if (!result.isSuccessful) throw ChatApiException.from(result.code, result.retryAfter, result.body)
            return ChatJson.decodeFromString<T>(result.body)
        }

        /** Exponential backoff from 1 s, capped, with jitter so devices do not reconnect in lockstep. */
        internal fun backoffMillis(attempt: Int, maxMillis: Long): Long {
            val ceiling = minOf(maxMillis, 1000L shl minOf(attempt, 20))
            return ceiling / 2 + Random.nextLong(ceiling / 2 + 1)
        }

        internal fun decodeEvent(data: String): ChatStreamEvent? {
            val obj = runCatching { ChatJson.decodeFromString<JsonObject>(data) }.getOrNull() ?: return null
            val type = runCatching { obj["type"]?.jsonPrimitive?.content }.getOrNull() ?: return null
            return runCatching {
                when (type) {
                    "connected" -> ChatJson.decodeFromString<ConnectedPayload>(data)
                        .let { ChatStreamEvent.Connected(it.appId, it.endUserId) }
                    "message" -> ChatStreamEvent.Message(ChatJson.decodeFromString<ChatMessage>(data))
                    "rating" -> ChatJson.decodeFromString<RatingPayload>(data)
                        .let { ChatStreamEvent.Rating(it.messageId, it.answer) }
                    else -> ChatStreamEvent.Unknown(type, data)
                }
            }.getOrNull()
        }
    }

    @Serializable
    private class ConnectedPayload(val appId: String, val endUserId: String)

    @Serializable
    private class RatingPayload(val messageId: String, val answer: RatingAnswer)

    // region Requests

    /** Sends a text message, or a media URL returned by [upload]. */
    public suspend fun sendMessage(content: String, type: MessageType = MessageType.TEXT): SendMessageResult {
        val body = buildJsonObject {
            put("content", content)
            put("messageType", type.value)
        }.toString().toRequestBody(JSON)
        return authed("message") { post(body) }
    }

    /**
     * Uploads an image (JPEG, PNG, WebP, GIF), audio clip or PDF. Requires a
     * server-minted token. The server checks the bytes against [mimeType].
     */
    public suspend fun upload(bytes: ByteArray, filename: String, mimeType: String): UploadResult =
        upload(bytes.toRequestBody(mimeType.toMediaType()), filename)

    public suspend fun upload(file: File, mimeType: String): UploadResult =
        upload(file.asRequestBody(mimeType.toMediaType()), file.name)

    private suspend fun upload(part: RequestBody, filename: String): UploadResult {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, part)
            .build()
        return authed("upload") { post(form) }
    }

    /** Uploads a file and sends it as a message in one call. */
    public suspend fun sendMedia(bytes: ByteArray, filename: String, mimeType: String): SendMessageResult {
        val uploaded = upload(bytes, filename, mimeType)
        return sendMessage(uploaded.url, uploaded.messageType)
    }

    public suspend fun config(): AppConfig = authed("config") { get() }

    /** The newest unanswered rating card, for showing one the stream delivered while the app was closed. */
    public suspend fun pendingRating(): PendingRatingCard? =
        authed<PendingResponse>("rating") { get() }.pending

    @Serializable
    private class PendingResponse(val pending: PendingRatingCard? = null)

    public suspend fun submitRating(messageId: String, score: Int, comment: String? = null): SubmitRatingResult {
        val body = buildJsonObject {
            put("messageId", messageId)
            put("score", score)
            if (comment != null) put("comment", comment)
        }.toString().toRequestBody(JSON)
        return authed("rating") { post(body) }
    }

    /** Drops the cached token, e.g. when the user signs out. */
    public suspend fun clearSession() {
        tokens.clear()
    }

    private suspend inline fun <reified T> authed(
        path: String,
        noinline method: Request.Builder.() -> Request.Builder,
    ): T = decode(execute(path, method))

    /** Sends with the session token; on 401 refreshes it once and retries. */
    private suspend fun execute(path: String, method: Request.Builder.() -> Request.Builder): HttpResult {
        suspend fun send(force: Boolean): HttpResult {
            val request = Request.Builder()
                .url(endpoint(base, path))
                .header("Authorization", "Bearer ${tokens.get(force)}")
                .method()
                .build()
            val client = if (request.method == "POST") postHttp else http
            return client.newCall(request).awaitResult()
        }
        val first = send(force = false)
        return if (first.code == 401) send(force = true) else first
    }

    // endregion

    // region Stream

    /**
     * The reply stream as a cold [Flow]. Collecting it connects; it reconnects
     * on its own, with a fresh token when needed, until the collector is
     * cancelled. It completes only if the app is deactivated or deleted (404).
     */
    @JvmOverloads
    public fun events(options: StreamOptions = StreamOptions()): Flow<ChatStreamEvent> = channelFlow {
        // OkHttp's read timeout is the longest gap between packets, which is
        // exactly the dead-connection watchdog we want: the server sends a
        // heartbeat every 15 s.
        val streamHttp = http.newBuilder()
            .readTimeout(options.idleTimeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val factory = EventSources.createFactory(streamHttp)

        var state = StreamState.CONNECTING
        suspend fun setState(next: StreamState) {
            if (next == state) return
            state = next
            send(ChatStreamEvent.StateChanged(next))
        }
        send(ChatStreamEvent.StateChanged(StreamState.CONNECTING))

        var attempt = 0
        var forceRefresh = false
        while (true) {
            if (attempt > 0) setState(StreamState.RECONNECTING)
            var minDelayMillis = 0L
            try {
                val token = tokens.get(forceRefresh)
                forceRefresh = false
                val request = Request.Builder()
                    .url(endpoint(base, "stream"))
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "text/event-stream")
                    .build()
                val ended = openOnce(factory, request) { event ->
                    if (event is ChatStreamEvent.Connected) {
                        attempt = 0
                        setState(StreamState.OPEN)
                    }
                    send(event)
                }
                if (ended is Signal.Failed) {
                    val code = ended.code
                    when {
                        code == 404 -> {
                            // The app was deactivated or deleted; retrying cannot help.
                            send(ChatStreamEvent.Error(ChatApiException.from(404, null, ended.body)))
                            setState(StreamState.CLOSED)
                            return@channelFlow
                        }
                        code != null -> {
                            if (code == 401) forceRefresh = true
                            throw ChatApiException.from(code, ended.retryAfter, ended.body)
                        }
                        else -> throw ended.error ?: java.io.IOException("Stream failed")
                    }
                }
                // A clean end: the server closes streams on deploy. Reconnect promptly.
            } catch (e: Exception) {
                // A CancellationException is either the collector cancelling
                // (ensureActive rethrows it) or one the token provider threw,
                // e.g. its own withTimeout. The latter is an ordinary failure.
                if (e is CancellationException) ensureActive()
                // A rate limit (on the stream or on minting its token) says how long to wait.
                (e as? ChatApiException)?.retryAfter?.let { minDelayMillis = it * 1000L }
                send(ChatStreamEvent.Error(e))
            }
            setState(StreamState.RECONNECTING)
            delay(maxOf(backoffMillis(attempt, options.maxBackoffMillis), minDelayMillis))
            attempt += 1
        }
    }

    private sealed interface Signal {
        class Data(val data: String) : Signal
        object Closed : Signal
        class Failed(val code: Int?, val retryAfter: String?, val body: String, val error: Throwable?) : Signal
    }

    /**
     * One connection. Returns how it ended. OkHttp calls the listener on its
     * own thread, so events cross into the coroutine through a channel.
     */
    private suspend fun openOnce(
        factory: EventSource.Factory,
        request: Request,
        onEvent: suspend (ChatStreamEvent) -> Unit,
    ): Signal {
        val signals = Channel<Signal>(Channel.UNLIMITED)
        val source = factory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                signals.trySend(Signal.Data(data))
            }

            override fun onClosed(eventSource: EventSource) {
                signals.trySend(Signal.Closed)
                signals.close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val body = runCatching { response?.body?.string() }.getOrNull().orEmpty()
                signals.trySend(Signal.Failed(response?.code, response?.header("Retry-After"), body, t))
                signals.close()
            }
        })
        try {
            for (signal in signals) {
                when (signal) {
                    is Signal.Data -> decodeEvent(signal.data)?.let { onEvent(it) }
                    else -> return signal
                }
            }
            return Signal.Closed
        } finally {
            source.cancel()
        }
    }

    // endregion
}
