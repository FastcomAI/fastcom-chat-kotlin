package com.fastcom.chat

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatClientTest {
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

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun bodyOf(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun publishableKeyMintsThenSends() = runBlocking {
        server.enqueue(json("""{"token":"session","expiresIn":1800,"tokenType":"Bearer"}"""))
        server.enqueue(json("""{"messageId":"m1","conversationWithId":"c1"}""", 201))
        val client = ChatClient.withPublishableKey(baseUrl, "pk_live_x", "u1", "Jane")

        val result = client.sendMessage("hi")

        assertEquals("m1", result.messageId)
        val mint = server.takeRequest()
        assertEquals("/app/api/v1/chat/token", mint.path)
        assertEquals("Bearer pk_live_x", mint.getHeader("Authorization"))
        assertEquals(bodyOf("""{"endUserId":"u1","endUserName":"Jane"}"""), bodyOf(mint.body.readUtf8()))
        val send = server.takeRequest()
        assertEquals("/app/api/v1/chat/message", send.path)
        assertEquals("Bearer session", send.getHeader("Authorization"))
        assertEquals(bodyOf("""{"content":"hi","messageType":"TEXT"}"""), bodyOf(send.body.readUtf8()))
    }

    @Test
    fun refreshesOnceOn401() = runBlocking {
        server.enqueue(json("""{"error":"Invalid or expired session token"}""", 401))
        server.enqueue(json("""{"appId":"a","name":"App","botConnected":true,"botActive":false}"""))
        val n = AtomicInteger()
        val client = ChatClient(baseUrl) { SessionToken("t${n.incrementAndGet()}") }

        val config = client.config()

        assertFalse(config.botActive)
        assertEquals("Bearer t1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer t2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun appWide429Error() = runBlocking {
        server.enqueue(
            json("""{"error":"App usage limit reached","limit":"messagesPerHour"}""", 429).setHeader("Retry-After", "120"),
        )
        val client = ChatClient(baseUrl) { SessionToken("t") }

        val error = assertFailsWith<ChatApiException> { client.sendMessage("x") }

        assertEquals(429, error.status)
        assertEquals(120, error.retryAfter)
        assertEquals("messagesPerHour", error.limit)
        assertEquals("App usage limit reached", error.error)
    }

    @Test
    fun uploadSendsMultipartThenMessage() = runBlocking {
        server.enqueue(json("""{"url":"https://storage/x.png","messageType":"IMAGE"}""", 201))
        server.enqueue(json("""{"messageId":"m","conversationWithId":"c"}""", 201))
        val client = ChatClient(baseUrl) { SessionToken("t") }

        client.sendMedia(byteArrayOf(0x89.toByte(), 0x50), "photo.png", "image/png")

        val upload = server.takeRequest()
        assertEquals("/app/api/v1/chat/upload", upload.path)
        assertTrue(upload.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val multipart = upload.body.readUtf8()
        assertTrue(multipart.contains("""name="file"; filename="photo.png""""))
        assertTrue(multipart.contains("Content-Type: image/png"))
        val send = server.takeRequest()
        assertEquals(bodyOf("""{"content":"https://storage/x.png","messageType":"IMAGE"}"""), bodyOf(send.body.readUtf8()))
    }

    @Test
    fun pendingRatingCard() = runBlocking {
        server.enqueue(
            json(
                """{"pending":{"messageId":"m9","sentAt":"2026-10-27T10:00:00.000Z","type":"rating_card","title":"How did we do?",
                "subtitle":"Sub","question":null,"scoreOption":"five_emojis","comment":true,"submitLabel":"Rate us","answer":null}}""",
            ),
        )
        val client = ChatClient(baseUrl) { SessionToken("t") }

        val pending = client.pendingRating()

        assertEquals("m9", pending?.messageId)
        assertEquals(RatingScoreOption.FIVE_EMOJIS, pending?.card?.scoreOption)
        assertNull(pending?.question)
    }

    @Test
    fun streamDeliversEventsReconnectsAndStopsOn404() = runBlocking {
        val sse = MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"type\":\"connected\",\"appId\":\"a\",\"endUserId\":\"u\"}\n\n" +
                    ": heartbeat\n\n" +
                    "data: {\"type\":\"message\",\"messageId\":\"m1\",\"content\":\"hello\",\"messageType\":\"TEXT\"," +
                    "\"isFromPage\":true,\"platformMessageId\":\"p\",\"timestamp\":\"t\"}\n\n",
            )
        server.enqueue(sse)
        server.enqueue(MockResponse().setResponseCode(401).setBody("Invalid or expired session token"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("App not found"))
        val n = AtomicInteger()
        val client = ChatClient(baseUrl) { SessionToken("t${n.incrementAndGet()}") }

        val events = withTimeout(10_000) { client.events(StreamOptions(maxBackoffMillis = 20)).toList() }

        val messages = events.filterIsInstance<ChatStreamEvent.Message>().map { it.message.content }
        assertEquals(listOf("hello"), messages)
        val errors = events.filterIsInstance<ChatStreamEvent.Error>().map { (it.error as ChatApiException).status }
        assertEquals(listOf(401, 404), errors)
        val states = events.filterIsInstance<ChatStreamEvent.StateChanged>().map { it.state }
        assertEquals(StreamState.CONNECTING, states.first())
        assertTrue(StreamState.OPEN in states)
        assertTrue(StreamState.RECONNECTING in states)
        assertEquals(StreamState.CLOSED, states.last())
        assertEquals(listOf("Bearer t1", "Bearer t1", "Bearer t2"), List(3) { server.takeRequest().getHeader("Authorization") })
    }

    @Test
    fun cancellingTheCollectorStopsTheStream() = runBlocking {
        repeat(5) {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"type\":\"connected\",\"appId\":\"a\",\"endUserId\":\"u\"}\n\n"),
            )
        }
        val client = ChatClient(baseUrl) { SessionToken("t") }

        val connected = withTimeout(10_000) {
            client.events(StreamOptions(maxBackoffMillis = 10))
                .take(4) // CONNECTING, Connected, OPEN, ... then cancel
                .toList()
        }

        assertTrue(connected.any { it is ChatStreamEvent.Connected })
        Thread.sleep(200)
        assertTrue(server.requestCount <= 2)
    }

    @Test
    fun decodesEvents() {
        val card = ChatClient.decodeEvent(
            """{"type":"message","messageId":"m","content":"q","messageType":"TEXT","isFromPage":true,"platformMessageId":"p",
            "timestamp":"t","interaction":{"type":"rating_card","title":"T","subtitle":"S","question":null,
            "scoreOption":"five_stars","comment":false,"submitLabel":"Go","answer":null}}""",
        ) as ChatStreamEvent.Message
        assertEquals("Go", card.message.ratingCard?.submitLabel)

        val future = ChatClient.decodeEvent(
            """{"type":"message","messageId":"m","content":"q","messageType":"CAROUSEL","isFromPage":true,
            "platformMessageId":"p","timestamp":"t","interaction":{"type":"poll"}}""",
        ) as ChatStreamEvent.Message
        assertNull(future.message.ratingCard)
        assertEquals("CAROUSEL", future.message.messageType.value)
        assertNotNull(future.message.interaction?.jsonObject?.get("type")?.jsonPrimitive)

        val unknown = ChatClient.decodeEvent("""{"type":"typing"}""") as ChatStreamEvent.Unknown
        assertEquals("typing", unknown.type)

        val rating = ChatClient.decodeEvent("""{"type":"rating","messageId":"m","answer":{"score":5,"comment":null,"at":"t"}}""")
        assertEquals(ChatStreamEvent.Rating("m", RatingAnswer(5, null, "t")), rating)
    }

    @Test
    fun backoffStaysWithinBounds() {
        repeat(100) {
            val first = ChatClient.backoffMillis(0, 30_000)
            assertTrue(first in 500..1000)
            val capped = ChatClient.backoffMillis(40, 30_000)
            assertTrue(capped in 15_000..30_000)
        }
    }
}
