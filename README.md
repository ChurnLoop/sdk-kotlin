# ChurnLoop Kotlin SDK

[![Maven Central](https://img.shields.io/maven-central/v/io.churnloop/churnloop-sdk-kotlin)](https://central.sonatype.com/artifact/io.churnloop/churnloop-sdk-kotlin)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-API%2024%2B-green)](https://developer.android.com)
[![JVM](https://img.shields.io/badge/JVM-11%2B-orange)](https://kotlinlang.org)

Official Kotlin SDK for the [ChurnLoop](https://churnloop.com) analytics + intervention platform.

- Pure JVM Kotlin — works on Android (API 24+) and any server-side JVM (Ktor, Spring, etc.)
- Single runtime dependency: `kotlinx-coroutines-core`
- Built-in HTTP via `HttpURLConnection` (no OkHttp dependency — keeps Android binary size down)
- Coroutines-first API (suspending `close()`); `closeBlocking()` available for Java and non-suspending teardown
- Batching with size + time + flush triggers
- Exponential-backoff retry on transient failures
- Inbound-webhook signature verification helper included

> **Status:** v0.2.0. `track()`, `identify()`, `page()`, `screen()`, batching, exponential-backoff retry, and webhook verification all work end-to-end.

---

## Contents

- [Install](#install)
- [Quick start](#quick-start)
- [Configuration](#configuration)
- [Canonical event names](#canonical-event-names)
- [Webhook signature verification](#webhook-signature-verification)
- [Android lifecycle integration](#android-lifecycle-integration)
- [Privacy & consent](#privacy--consent)
- [Error handling](#error-handling)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)

---

## Install

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.churnloop:churnloop-sdk-kotlin:0.2.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'io.churnloop:churnloop-sdk-kotlin:0.2.0'
}
```

### Android-specific notes

- **Min SDK 24.** Earlier APIs lack `java.time.*` (used in the webhook verifier).
- Already using a coroutines dependency? You're set — the SDK uses your existing `kotlinx-coroutines-core`.
- Add the internet permission:
  ```xml
  <uses-permission android:name="android.permission.INTERNET" />
  ```

---

## Quick start

```kotlin
import io.churnloop.sdk.ChurnLoop
import io.churnloop.sdk.ChurnLoopOptions
import io.churnloop.sdk.StandardEvent

val churnloop = ChurnLoop(ChurnLoopOptions(apiKey = "cl_..."))

churnloop.track(
    event = StandardEvent.UserSignedUp,
    userId = "user_123",
    properties = mapOf(
        "plan" to "free",
        "source" to "organic",
    ),
)

// Custom event:
churnloop.track(
    event = "Cart Abandoned",
    userId = "user_123",
    properties = mapOf("items" to 3, "value_usd" to 89.5),
)

// On app shutdown — suspending close (drains in-flight sends):
runBlocking { churnloop.close() }
// ... or from Java / non-suspending teardown:
churnloop.closeBlocking()
```

---

## Configuration

```kotlin
val options = ChurnLoopOptions(
    apiKey = "cl_...",                                  // required
    host = "https://ingest.churnloop.com",              // default
    timeoutMs = 30_000,
    disabled = false,
    flushAt = 100,           // flush when queue reaches this many events
    flushInterval = 1_000L,  // flush if events sit longer than this (ms)
    maxQueueSize = 1_000,    // drop oldest when exceeded
    maxRetries = 5,          // retry retryable failures with backoff
    onError = { error, droppedCount ->
        Timber.w(error, "ChurnLoop send failed (dropped: $droppedCount)")
    },
)
val churnloop = ChurnLoop(options)
```

### `disabled` mode

`disabled = true` makes every `track(...)` call a no-op. Useful for:

- Unit tests in your app code (no fake network calls)
- Debug builds (events don't end up in production telemetry)
- Consent flows where the user opted out (see [Privacy & consent](#privacy--consent))

---

## Canonical event names

Use `StandardEvent` for cross-tenant features:

```kotlin
churnloop.track(StandardEvent.UserSignedUp,        userId = "...")
churnloop.track(StandardEvent.OnboardingCompleted, userId = "...")
churnloop.track(
    event = StandardEvent.SubscriptionStarted,
    userId = "...",
    properties = mapOf("plan" to "growth"),
)
```

Why use them:

- **Built-in dashboards** — your activation funnel (`UserSignedUp` → `OnboardingCompleted`) works without configuration
- **Playbook templates** — drop-in interventions that recognise standard events
- **Cross-tenant benchmarks** — privacy-respecting aggregates ("your activation rate vs. the p50 across all ChurnLoop customers")

Values match the JS + Swift SDKs byte-for-byte — events from your Android app, your iOS app, and your web app land under the same names server-side.

---

## Webhook signature verification

For server-side Kotlin services receiving outbound webhooks from ChurnLoop:

```kotlin
import io.churnloop.sdk.WebhookVerify
import io.churnloop.sdk.VerifyResult

val result = WebhookVerify.verify(
    rawBody = bodyString,                       // byte-exact body (UTF-8)
    signatureHeader = call.request.headers["X-ChurnLoop-Signature"] ?: "",
    secret = System.getenv("CHURNLOOP_WEBHOOK_SECRET"),
)

when (result) {
    is VerifyResult.Valid -> {
        val event = Json.decodeFromString<ChurnLoopEvent>(bodyString)
        // ... handle event ...
    }
    is VerifyResult.Invalid -> {
        // Log the reason; do NOT return it to the sender
        // (would be a verification oracle).
        log.warn("invalid ChurnLoop webhook: ${result.reason}")
        call.respond(HttpStatusCode.Forbidden)
    }
}
```

### Contract

| Field | Value |
|---|---|
| Signing header | `X-ChurnLoop-Signature: t=<unix>,v1=<hex>` |
| Algorithm | HMAC-SHA256 over `${timestamp}.${rawBody}` |
| Default tolerance | 300 seconds (5 minutes) |
| Replay protection | Reject signatures older than `toleranceSeconds` |
| Constant-time comparison | Yes (timing-attack-safe) |

`WebhookVerify.verify(...)` never throws — failures are returned as `VerifyResult.Invalid(reason)`.

### Ktor route example

```kotlin
routing {
    post("/webhooks/churnloop") {
        val rawBody = call.receiveText()
        val sig = call.request.headers["X-ChurnLoop-Signature"] ?: ""
        val result = WebhookVerify.verify(rawBody, sig, System.getenv("CHURNLOOP_WEBHOOK_SECRET"))
        when (result) {
            is VerifyResult.Valid -> {
                // handle
                call.respond(HttpStatusCode.OK)
            }
            is VerifyResult.Invalid -> call.respond(HttpStatusCode.Forbidden)
        }
    }
}
```

---

## Android lifecycle integration

Hold one `ChurnLoop` instance per app process — typically in a `@Singleton` / DI provider. Wire `closeBlocking()` into your app's process-shutdown so the in-flight sends drain:

```kotlin
@Singleton
class ChurnLoopHolder @Inject constructor() {
    val client = ChurnLoop(ChurnLoopOptions(apiKey = BuildConfig.CHURNLOOP_API_KEY))
}

// In your Application class:
class MyApp : Application() {
    @Inject lateinit var churnloopHolder: ChurnLoopHolder

    override fun onTerminate() {
        super.onTerminate()
        churnloopHolder.client.closeBlocking()
    }
}
```

For Activity-level flush before the user backgrounds the app, use a `LifecycleObserver`:

```kotlin
class FlushOnPauseObserver(private val churnloop: ChurnLoop) : DefaultLifecycleObserver {
    override fun onPause(owner: LifecycleOwner) {
        owner.lifecycleScope.launch {
            churnloop.close()
        }
    }
}
```

---

## Privacy & consent

The SDK does NOT check any consent signal automatically. Gate the `ChurnLoop` constructor behind your own consent infrastructure:

```kotlin
val churnloop = if (userConsented) {
    ChurnLoop(ChurnLoopOptions(apiKey = "cl_..."))
} else {
    ChurnLoop(ChurnLoopOptions(apiKey = "cl_...", disabled = true))
}
```

This keeps the consent boundary where it belongs — with the integration that knows your app's jurisdiction, your privacy policy, and your user's choices — rather than building a half-baked consent model into the SDK.

---

## Error handling

`track()` never throws. Errors flow through the `onError` callback (defaults to `System.err`):

```kotlin
val options = ChurnLoopOptions(
    apiKey = "cl_...",
    onError = { error, dropped ->
        when (error) {
            is TransportError -> {
                Crashlytics.recordException(error)
                Crashlytics.setCustomKey("churnloop_status", error.statusCode ?: 0)
            }
            else -> Timber.w(error)
        }
    },
)
```

Errors the SDK reports:

| Cause | Reported via | Retryable? |
|---|---|---|
| Missing `event` or `userId` | `onError` (dropped locally) | No (caller bug) |
| Invalid API key (401) | `onError` | No |
| Insufficient permissions or quota exceeded (403) | `onError` | No |
| Validation failure on the server (400) | `onError` | No |
| Rate-limited (429) | `onError` after retries exhausted | Yes (exponential backoff) |
| 5xx / network failure | `onError` after retries exhausted | Yes (exponential backoff) |

---

## Roadmap

| Version | Adds |
|---|---|
| **v0.1.0** | `track()`, `StandardEvent`, `$context`, webhook verification |
| **v0.2.0** *(current)* | `identify`, `page`, `screen`; batching (size + time + shutdown); exponential-backoff retry; queue overflow drop policy; `closeBlocking()` |
| **v0.3.0** | Persisted queue (survives process kill); Android-specific device fields in `$context` |
| **v1.0.0** | Public API frozen; SemVer guarantees |

---

## Contributing

Issues and PRs welcome at [github.com/ChurnLoop/sdk-kotlin](https://github.com/ChurnLoop/sdk-kotlin/issues).

---

## License

Apache-2.0
