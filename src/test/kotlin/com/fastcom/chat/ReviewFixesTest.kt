package com.fastcom.chat

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewFixesTest {
    private lateinit var server: MockWebServer
    private val baseUrl get() = server.url("/app/").toString()

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private val connectedSse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"type\":\"connected\",\"appId\":\"a\",\"endUserId\":\"u\"}\n\n")

    @Test
    fun refusesASecretKeyAsPublishableKeyWithoutEchoingIt() {
        val error = assertFailsWith<IllegalArgumentException> {
            ChatClient.withPublishableKey(baseUrl, "sk_live_supersecret", "u")
        }
        assertTrue(error.message!!.contains("secret key"))
        assertFalse(error.message!!.contains("supersecret"))
        assertFailsWith<IllegalArgumentException> { ChatClient.withPublishableKey(baseUrl, "nope", "u") }
    }

    @Test
    fun aTimeoutInsideTheTokenProviderIsRetriedNotFatal() = runBlocking {
        server.enqueue(connectedSse)
        server.enqueue(MockResponse().setResponseCode(404).setBody("App not found"))
        val calls = AtomicInteger()
        val client = ChatClient(baseUrl) {
            if (calls.incrementAndGet() == 1) withTimeout(20) { delay(1_000) }
            SessionToken("t")
        }

        val events = withTimeout(10_000) { client.events(StreamOptions(maxBackoffMillis = 20)).toList() }

        val firstError = events.filterIsInstance<ChatStreamEvent.Error>().first().error
        assertTrue(firstError is kotlinx.coroutines.TimeoutCancellationException, "got $firstError")
        assertTrue(events.any { it is ChatStreamEvent.Connected }, "the stream should recover: $events")
        assertEquals(ChatStreamEvent.StateChanged(StreamState.CLOSED), events.last())
    }

    @Test
    fun aDroppedConnectionDoesNotSendAMessageTwice() = runBlocking {
        // Warm a pooled connection, then drop it right after the POST is sent.
        server.enqueue(MockResponse().setBody("""{"appId":"a","name":"n","botConnected":true,"botActive":true}"""))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"messageId":"m","conversationWithId":"c"}"""))
        val client = ChatClient(baseUrl) { SessionToken("t") }
        client.config()

        assertFailsWith<IOException> { client.sendMessage("hi") }

        assertEquals(2, server.requestCount, "the POST must not be resent")
    }

    @Test
    fun reconnectWaitsAtLeastRetryAfter() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1").setBody("Rate limit exceeded"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("App not found"))
        val client = ChatClient(baseUrl) { SessionToken("t") }

        val started = System.nanoTime()
        val events = withTimeout(10_000) { client.events(StreamOptions(maxBackoffMillis = 10)).toList() }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        val first = events.filterIsInstance<ChatStreamEvent.Error>().first().error as ChatApiException
        assertEquals(429, first.status)
        assertEquals(1, first.retryAfter)
        assertTrue(elapsedMs >= 950, "reconnected after $elapsedMs ms")
    }
}
