# Fastcom Chat (Kotlin)

Kotlin SDK for the Mobile Chat API, for Android and any JVM app.
It is built on OkHttp, kotlinx.coroutines and kotlinx.serialization, and targets Java 8 bytecode.

## Install

```kotlin
dependencies {
    implementation("io.github.fastcomai:fastcom-chat:0.1.0")
}
```

The package comes from Maven Central, which Android and Gradle projects use by default.

## Use

```kotlin
import com.fastcom.chat.*

val chat = ChatClient("https://your-domain.com/app") {
    // Your backend mints the token with the secret key and returns the /token response as-is.
    myBackend.chatToken() // -> SessionToken(token, expiresIn)
}

// The reply stream is a cold Flow. It reconnects by itself until the collector is cancelled.
lifecycleScope.launch {
    chat.events().collect { event ->
        when (event) {
            is ChatStreamEvent.Message -> {
                val card = event.message.ratingCard
                if (card != null) showRatingCard(card, event.message.messageId)
                else showBubble(event.message.content, event.message.messageType)
            }
            is ChatStreamEvent.Rating -> markCardAnswered(event.messageId, event.answer)
            is ChatStreamEvent.StateChanged -> updateBanner(event.state)
            is ChatStreamEvent.Error -> Log.w("chat", event.error)
            is ChatStreamEvent.Connected, is ChatStreamEvent.Unknown -> Unit
        }
    }
}

chat.sendMessage("Where is my order?")
chat.sendMedia(jpegBytes, "photo.jpg", "image/jpeg")

chat.pendingRating()?.let { showRatingCard(it.card, it.messageId) }
chat.submitRating(messageId = "…", score = 5, comment = "Great")
```

Without a backend, use the publishable key.
This must be enabled on the app card, the end user id is self-declared, and these sessions cannot upload:

```kotlin
val chat = ChatClient.withPublishableKey(
    baseUrl = "https://your-domain.com/app",
    publishableKey = "pk_live_...",
    endUserId = deviceId,
    endUserName = "Jane",
)
```

Every call is a `suspend` function that does its network I/O off the calling thread, so it is safe to call from the main thread.
Pass your own `OkHttpClient` to share its connection pool or add interceptors.

## Errors

Every non-2xx response throws a `ChatApiException` (an `IOException`) with `status`, `error`, `retryAfter` (seconds, on a `429`) and `limit` (set when an app-wide cap was hit).

## Development

You need a JDK 17+.
The Gradle wrapper is pinned to Gradle 8.10.2, which matches the Kotlin 2.0 plugin; it downloads itself on first run.

```bash
./gradlew test
./gradlew publishToMavenLocal
```

On macOS with Homebrew's `openjdk@17`, point `JAVA_HOME` at it first:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
```

`publishToMavenLocal` needs no signing key.

## License

MIT, see [LICENSE](LICENSE).
