package com.fastcom.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * A message type. Modeled as an open set so a type added on the server later
 * still decodes; compare against the constants.
 */
@Serializable
@JvmInline
public value class MessageType(public val value: String) {
    public companion object {
        public val TEXT: MessageType = MessageType("TEXT")
        public val IMAGE: MessageType = MessageType("IMAGE")
        public val FILE: MessageType = MessageType("FILE")
        public val AUDIO: MessageType = MessageType("AUDIO")
        public val VIDEO: MessageType = MessageType("VIDEO")
        /** Receive-only. */
        public val LINK: MessageType = MessageType("LINK")
    }
}

/** The `/token` response. Your backend can return it as-is from its own endpoint. */
@Serializable
public data class SessionToken(
    val token: String,
    /** Seconds until expiry. When null, the token is refreshed only after a 401. */
    val expiresIn: Long? = null,
)

@Serializable
public data class SendMessageResult(
    val messageId: String,
    val conversationWithId: String,
)

@Serializable
public data class UploadResult(
    /** Send this as the content of a message with [messageType]. */
    val url: String,
    /** `IMAGE`, `AUDIO` or `FILE`. */
    val messageType: MessageType,
)

@Serializable
public data class AppConfig(
    val appId: String,
    val name: String,
    val botConnected: Boolean,
    /** Whether an AI agent will actually answer on this app right now. */
    val botActive: Boolean,
)

/** How a rating card draws its five choices: `five_stars`, `one_to_five` or `five_emojis`. Scores are always 1-5. */
@Serializable
@JvmInline
public value class RatingScoreOption(public val value: String) {
    public companion object {
        public val FIVE_STARS: RatingScoreOption = RatingScoreOption("five_stars")
        public val ONE_TO_FIVE: RatingScoreOption = RatingScoreOption("one_to_five")
        public val FIVE_EMOJIS: RatingScoreOption = RatingScoreOption("five_emojis")
    }
}

@Serializable
public data class RatingAnswer(
    /** Whole number, 1-5. */
    val score: Int,
    val comment: String? = null,
    /** ISO 8601 timestamp. */
    val at: String,
)

/** A rating card, carried as `interaction` on a message. */
@Serializable
public data class RatingCard(
    val title: String,
    val subtitle: String,
    val question: String? = null,
    val scoreOption: RatingScoreOption,
    /** Offer an optional comment box (up to 1000 characters). */
    val comment: Boolean,
    val submitLabel: String,
    /** Null while the card is open; draw an answered card as a thank-you. */
    val answer: RatingAnswer? = null,
)

/** `GET /rating`: the newest unanswered card. */
@Serializable
public data class PendingRatingCard(
    val messageId: String,
    val sentAt: String,
    val title: String,
    val subtitle: String,
    val question: String? = null,
    val scoreOption: RatingScoreOption,
    val comment: Boolean,
    val submitLabel: String,
    val answer: RatingAnswer? = null,
) {
    public val card: RatingCard
        get() = RatingCard(title, subtitle, question, scoreOption, comment, submitLabel, answer)
}

@Serializable
public data class SubmitRatingResult(
    /** `recorded`, or `already_rated` when the ticket already had a score ([answer] is the one that counted). */
    val status: String,
    val answer: RatingAnswer,
)

/** A reply from the business (bot or human), as delivered on the stream. */
@Serializable
public data class ChatMessage(
    val messageId: String,
    /** Plain text for TEXT and LINK, the media URL otherwise. */
    val content: String,
    val messageType: MessageType,
    val isFromPage: Boolean,
    val platformMessageId: String,
    val timestamp: String,
    /** Raw interaction JSON. Use [ratingCard]; ignore types you do not know and show [content]. */
    val interaction: JsonElement? = null,
    /** Set when one bot reply was split into several messages. */
    val groupId: String? = null,
    val groupIndex: Int? = null,
    val groupSize: Int? = null,
) {
    /** Set when the message should be drawn as a rating card instead of a bubble. */
    public val ratingCard: RatingCard?
        get() {
            val obj = interaction as? JsonObject ?: return null
            if (obj["type"]?.jsonPrimitive?.content != "rating_card") return null
            return runCatching { ChatJson.decodeFromJsonElement<RatingCard>(obj) }.getOrNull()
        }
}

public enum class StreamState { CONNECTING, OPEN, RECONNECTING, CLOSED }

public sealed interface ChatStreamEvent {
    public data class Connected(val appId: String, val endUserId: String) : ChatStreamEvent

    /** A reply from the business, bot or human. */
    public data class Message(val message: ChatMessage) : ChatStreamEvent

    /** A rating card was answered, on this or another device. */
    public data class Rating(val messageId: String, val answer: RatingAnswer) : ChatStreamEvent

    /** An event type this SDK version does not know yet, as raw JSON. */
    public data class Unknown(val type: String, val json: String) : ChatStreamEvent

    public data class StateChanged(val state: StreamState) : ChatStreamEvent

    /** A connection attempt failed. The stream keeps retrying unless `StateChanged(CLOSED)` follows. */
    public data class Error(val error: Throwable) : ChatStreamEvent
}

public data class StreamOptions(
    /**
     * Reconnect when nothing (not even the 15 s heartbeat) arrives for this
     * long. Catches connections a mobile network dropped without closing.
     */
    val idleTimeoutMillis: Long = 45_000,
    /** Longest wait between reconnect attempts. */
    val maxBackoffMillis: Long = 30_000,
)
