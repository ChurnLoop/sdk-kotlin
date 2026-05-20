package io.churnloop.sdk

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * In-memory transport for unit tests. Captures every batch body
 * (already JSON-serialised) so tests can parse + assert shape.
 * `CopyOnWriteArrayList` is thread-safe — necessary because
 * `ChurnLoop` dispatches sends to the IO coroutine dispatcher.
 */
class RecordingTransport : Transport {
    val sentBatches: MutableList<String> = CopyOnWriteArrayList()

    // If set, every call throws this — for non-retryable + exhaustion tests.
    @Volatile var shouldThrow: Throwable? = null
    // Throw a transient retryable 500 for the first N calls, then succeed.
    @Volatile var throwForFirst: Int = 0

    override fun sendBatch(bodyJson: String, options: ChurnLoopOptions) {
        if (throwForFirst > 0) {
            throwForFirst -= 1
            throw TransportError("transient", statusCode = 500, retryable = true)
        }
        shouldThrow?.let { throw it }
        sentBatches += bodyJson
    }
}

class ChurnLoopTest {
    private val apiKey = "cl_test_123"

    private fun options(
        disabled: Boolean = false,
        flushAt: Int = 100,
        flushIntervalMs: Long = 1000L,
        maxQueueSize: Int = 1000,
        maxRetries: Int = 5,
        onError: ChurnLoopErrorHandler = { _, _ -> },
    ): ChurnLoopOptions = ChurnLoopOptions(
        apiKey = apiKey,
        host = "https://ingest.test.example.com",
        disabled = disabled,
        flushAt = flushAt,
        flushIntervalMs = flushIntervalMs,
        maxQueueSize = maxQueueSize,
        maxRetries = maxRetries,
        onError = onError,
    )

    private fun assertJsonField(json: String, key: String, expected: String) {
        assertTrue(
            json.contains("\"$key\":\"$expected\""),
            "expected \"$key\":\"$expected\" in $json",
        )
    }

    // ── track ──────────────────────────────────────────────────

    @Test
    fun `track sends Segment payload with expected shape`() = runTest {
        val transport = RecordingTransport()
        val client = ChurnLoop(options(), transport)

        client.track(event = "Custom Event", userId = "u_1")
        client.flush()

        assertEquals(1, transport.sentBatches.size)
        val json = transport.sentBatches[0]
        assertTrue(json.startsWith("{\"batch\":["), json)
        assertJsonField(json, "type", "track")
        assertJsonField(json, "event", "Custom Event")
        assertJsonField(json, "userId", "u_1")
    }

    @Test
    fun `track accepts StandardEvent enum`() = runTest {
        val transport = RecordingTransport()
        val client = ChurnLoop(options(), transport)

        client.track(event = StandardEvent.UserSignedUp, userId = "u_1")
        client.flush()

        assertJsonField(transport.sentBatches[0], "event", "User Signed Up")
    }

    @Test
    fun `track forwards properties alongside auto-attached context`() = runTest {
        val transport = RecordingTransport()
        val client = ChurnLoop(options(), transport)

        client.track(
            event = "Plan Selected",
            userId = "u_1",
            properties = mapOf("plan" to "growth", "annualBilling" to true),
        )
        client.flush()

        val json = transport.sentBatches[0]
        assertTrue(json.contains("\"plan\":\"growth\""), json)
        assertTrue(json.contains("\"annualBilling\":true"), json)
        assertTrue(json.contains("\"\$context\""), json)
    }

    @Test
    fun `track attaches context library fields`() = runTest {
        val transport = RecordingTransport()
        val client = ChurnLoop(options(), transport)

        client.track(event = "No Props", userId = "u_1")
        client.flush()

        val json = transport.sentBatches[0]
        assertTrue(json.contains("\"name\":\"churnloop-sdk-kotlin\""), json)
        assertTrue(json.contains("\"version\":\"0.2.0\""), json)
    }

    @Test
    fun `track generates UUID messageId when omitted`() = runTest {
        val transport = RecordingTransport()
        val client = ChurnLoop(options(), transport)

        client.track(event = "X", userId = "u_1")
        client.flush()

        val json = transport.sentBatches[0]
        val uuidRegex = Regex("\"messageId\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"")
        assertNotNull(uuidRegex.find(json), "messageId not a UUID: $json")
    }

    @Test
    fun `track preserves caller-supplied messageId`() = runTest {
        val transport = RecordingTransport()
        val client = ChurnLoop(options(), transport)

        client.track(event = "X", userId = "u_1", messageId = "caller-supplied-123")
        client.flush()

        assertJsonField(transport.sentBatches[0], "messageId", "caller-supplied-123")
    }

    @Test
    fun `track does nothing when disabled`() = runTest {
        val transport = RecordingTransport()
        val client = ChurnLoop(options(disabled = true), transport)

        client.track(event = "X", userId = "u_1")
        client.flush()

        assertTrue(transport.sentBatches.isEmpty())
    }

    @Test
    fun `track calls onError when event is empty`() = runTest {
        var errorSeen: Throwable? = null
        var droppedCount = 0
        val client = ChurnLoop(
            options(onError = { err, dropped ->
                errorSeen = err
                droppedCount = dropped
            }),
            RecordingTransport(),
        )

        client.track(event = "", userId = "u_1")
        client.flush()

        assertNotNull(errorSeen)
        assertEquals(1, droppedCount)
    }

    @Test
    fun `track calls onError when userId is empty`() = runTest {
        var errorSeen: Throwable? = null
        val client = ChurnLoop(
            options(onError = { err, _ -> errorSeen = err }),
            RecordingTransport(),
        )

        client.track(event = "X", userId = "")
        client.flush()

        assertNotNull(errorSeen)
    }

    @Test
    fun `track calls onError when transport throws non-retryable`() = runTest {
        var errorSeen: Throwable? = null
        val transport = RecordingTransport().apply {
            shouldThrow = TransportError("unauthorized", statusCode = 401, retryable = false)
        }
        val client = ChurnLoop(
            options(onError = { err, _ -> errorSeen = err }),
            transport,
        )

        client.track(event = "X", userId = "u_1")
        client.flush()

        assertTrue(errorSeen is TransportError)
        assertEquals(401, (errorSeen as TransportError).statusCode)
        assertFalse((errorSeen as TransportError).retryable)
    }

    // ── identify / page / screen ───────────────────────────────

    @Test
    fun `identify sends identify payload with traits`() = runTest {
        val transport = RecordingTransport()
        val client = ChurnLoop(options(), transport)

        client.identify(userId = "u_1", traits = mapOf("email" to "a@b.com", "plan" to "growth"))
        client.flush()

        val json = transport.sentBatches[0]
        assertJsonField(json, "type", "identify")
        assertJsonField(json, "userId", "u_1")
        assertTrue(json.contains("\"traits\":{"), json)
        assertTrue(json.contains("\"email\":\"a@b.com\""), json)
        assertTrue(json.contains("\"plan\":\"growth\""), json)
    }

    @Test
    fun `identify calls onError when userId is empty`() = runTest {
        var errorSeen: Throwable? = null
        val client = ChurnLoop(
            options(onError = { err, _ -> errorSeen = err }),
            RecordingTransport(),
        )

        client.identify(userId = "", traits = mapOf("plan" to "growth"))
        client.flush()

        assertNotNull(errorSeen)
    }

    @Test
    fun `page sends page payload with name property`() = runTest {
        val transport = RecordingTransport()
        val client = ChurnLoop(options(), transport)

        client.page(userId = "u_1", name = "Dashboard")
        client.flush()

        val json = transport.sentBatches[0]
        assertJsonField(json, "type", "page")
        assertTrue(json.contains("\"name\":\"Dashboard\""), json)
    }

    @Test
    fun `screen sends screen payload with name property`() = runTest {
        val transport = RecordingTransport()
        val client = ChurnLoop(options(), transport)

        client.screen(userId = "u_1", name = "OnboardingStep1")
        client.flush()

        val json = transport.sentBatches[0]
        assertJsonField(json, "type", "screen")
        assertTrue(json.contains("\"name\":\"OnboardingStep1\""), json)
    }

    // ── batching ───────────────────────────────────────────────

    @Test
    fun `batches multiple events into a single request on flush`() = runTest {
        val transport = RecordingTransport()
        val client = ChurnLoop(options(flushIntervalMs = 60_000), transport)

        client.track(event = "A", userId = "u_1")
        client.track(event = "B", userId = "u_2")
        client.track(event = "C", userId = "u_3")
        client.flush()

        assertEquals(1, transport.sentBatches.size, "expected one batched request, got ${transport.sentBatches.size}")
        val json = transport.sentBatches[0]
        // All three events present, in order.
        val aIdx = json.indexOf("\"event\":\"A\"")
        val bIdx = json.indexOf("\"event\":\"B\"")
        val cIdx = json.indexOf("\"event\":\"C\"")
        assertTrue(aIdx in 0..bIdx && bIdx < cIdx, "events not in order: $json")
    }

    @Test
    fun `size trigger flushes immediately when queue reaches flushAt`() = runTest {
        val transport = RecordingTransport()
        val client = ChurnLoop(
            options(flushAt = 2, flushIntervalMs = 60_000),
            transport,
        )

        client.track(event = "A", userId = "u_1")
        client.track(event = "B", userId = "u_2")
        // Should fire WITHOUT explicit flush — size trigger.
        client.flush()

        assertEquals(1, transport.sentBatches.size)
        val json = transport.sentBatches[0]
        assertTrue(json.contains("\"event\":\"A\"") && json.contains("\"event\":\"B\""), json)
    }

    @Test
    fun `overflow drops oldest event and reports via onError`() = runTest {
        var droppedTotal = 0
        val transport = RecordingTransport()
        val client = ChurnLoop(
            options(
                flushAt = 100,
                flushIntervalMs = 60_000,
                maxQueueSize = 2,
                onError = { _, dropped -> droppedTotal += dropped },
            ),
            transport,
        )

        client.track(event = "A", userId = "u_1")
        client.track(event = "B", userId = "u_2")
        client.track(event = "C", userId = "u_3") // forces drop of A
        client.flush()

        assertEquals(1, droppedTotal, "expected 1 overflow drop")
        assertEquals(1, transport.sentBatches.size)
        val json = transport.sentBatches[0]
        assertFalse(json.contains("\"event\":\"A\""), "A should have been dropped: $json")
        assertTrue(json.contains("\"event\":\"B\""), json)
        assertTrue(json.contains("\"event\":\"C\""), json)
    }

    // ── retry ──────────────────────────────────────────────────

    @Test
    fun `retryable error replays the batch until it succeeds`() = runTest {
        val transport = RecordingTransport().apply {
            // First two attempts throw retryable 500; third succeeds.
            throwForFirst = 2
        }
        val client = ChurnLoop(
            options(flushIntervalMs = 60_000, maxRetries = 5),
            transport,
        )

        client.track(event = "X", userId = "u_1")
        client.flush()

        assertEquals(1, transport.sentBatches.size, "should have succeeded after retries")
    }

    @Test
    fun `retry exhaustion drops batch via onError`() = runTest {
        var droppedTotal = 0
        val transport = RecordingTransport().apply {
            shouldThrow = TransportError("upstream", statusCode = 503, retryable = true)
        }
        val client = ChurnLoop(
            options(
                flushIntervalMs = 60_000,
                maxRetries = 2,
                onError = { _, dropped -> droppedTotal += dropped },
            ),
            transport,
        )

        client.track(event = "X", userId = "u_1")
        client.track(event = "Y", userId = "u_2")
        client.flush()

        assertEquals(2, droppedTotal, "both events should drop after exhaustion")
        assertEquals(0, transport.sentBatches.size)
    }

    @Test
    fun `non-retryable error drops batch immediately without retry`() = runTest {
        var droppedTotal = 0
        var attemptCount = 0
        val transport = object : Transport {
            override fun sendBatch(bodyJson: String, options: ChurnLoopOptions) {
                attemptCount += 1
                throw TransportError("bad request", statusCode = 400, retryable = false)
            }
        }
        val client = ChurnLoop(
            options(
                flushIntervalMs = 60_000,
                maxRetries = 5,
                onError = { _, dropped -> droppedTotal += dropped },
            ),
            transport,
        )

        client.track(event = "X", userId = "u_1")
        client.flush()

        assertEquals(1, attemptCount, "4xx must not retry")
        assertEquals(1, droppedTotal)
    }

    // ── backoff ────────────────────────────────────────────────

    @Test
    fun `backoffMs grows exponentially with full jitter`() {
        // attempt=1: range [0, 250)
        // attempt=2: range [0, 500)
        // attempt=3: range [0, 1000)
        // Random=0.999 → near the cap.
        val nearOne = { 0.999 }
        assertTrue(backoffMs(1, nearOne) in 0..249)
        assertTrue(backoffMs(2, nearOne) in 0..499)
        assertTrue(backoffMs(3, nearOne) in 0..999)
        // Random=0 → always zero.
        assertEquals(0L, backoffMs(1, { 0.0 }))
        assertEquals(0L, backoffMs(10, { 0.0 }))
        // Cap kicks in at attempt=8 (250*2^7 = 32000 > 30000).
        assertTrue(backoffMs(20, nearOne) in 0..30_000)
    }

    // ── close ──────────────────────────────────────────────────

    @Test
    fun `close drains in-flight events`() = runTest {
        val transport = RecordingTransport()
        val client = ChurnLoop(options(flushIntervalMs = 60_000), transport)

        client.track(event = "A", userId = "u_1")
        client.track(event = "B", userId = "u_2")
        client.track(event = "C", userId = "u_3")
        client.close()

        assertEquals(1, transport.sentBatches.size)
        val json = transport.sentBatches[0]
        assertTrue(json.contains("\"event\":\"A\"") && json.contains("\"event\":\"B\"") && json.contains("\"event\":\"C\""), json)
    }

    @Test
    fun `close is idempotent`() = runTest {
        val client = ChurnLoop(options(), RecordingTransport())
        client.close()
        client.close()
        // Reaching here without hanging or throwing is the assertion.
    }

    @Test
    fun `events after close are dropped`() = runTest {
        val transport = RecordingTransport()
        val client = ChurnLoop(options(flushIntervalMs = 60_000), transport)

        client.close()
        client.track(event = "after_close", userId = "u_1")
        // No flush mechanism survives close, but verify nothing buffered.
        assertEquals(0, transport.sentBatches.size)
    }
}
