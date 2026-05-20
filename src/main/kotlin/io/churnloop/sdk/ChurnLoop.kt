package io.churnloop.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID

/**
 * Stage 22e — Kotlin SDK v0.2.
 *
 * Architecture: synchronously-buffered queue + single async pump.
 * Mirrors the JS + Swift SDKs so behaviour is uniform across
 * platforms:
 *
 *   - `track` / `identify` / `page` / `screen` synchronously append
 *     a fully-formed event to the queue under a lock, then decide
 *     whether to trigger a flush. SYNC enqueue preserves call-site
 *     ordering — a public `track(A)` followed by `track(B)` always
 *     emits A before B.
 *   - Flush triggers: queue reaches `flushAt` (size trigger),
 *     `flushIntervalMs` elapses since first buffered event (time
 *     trigger), or `flush()` / `close()` is called.
 *   - At most one pump coroutine in flight per instance. The pump
 *     keeps draining while events are queued; new events arriving
 *     during a send get picked up on the next iteration.
 *   - Retryable failures replay the same batch (same messageIds, so
 *     server-side dedup holds) with exponential backoff + full
 *     jitter up to `maxRetries`. On exhaustion events drop to
 *     `onError`.
 *   - Non-retryable failures (4xx) drop the batch immediately —
 *     replaying would 4xx again.
 *
 * Public API mirrors the other SDKs: callers don't `await` per-event
 * methods — errors flow through `onError`, not as exceptions out of
 * these methods. `flush()` and `close()` are the only suspending
 * public methods.
 *
 * Concurrency: a `synchronized` block protects mutable state; we
 * never call into customer closures or launch coroutines while
 * holding the lock.
 */
public class ChurnLoop @JvmOverloads constructor(
    private val options: ChurnLoopOptions,
    private val transport: Transport = HttpUrlConnectionTransport(),
) {
    private val supervisor: Job = SupervisorJob()
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + supervisor)

    private val lock = Any()
    // All fields below are protected by `lock`. Never read/write
    // outside a synchronized(lock) { } region.
    private val queue: ArrayDeque<JsonValue.Obj> = ArrayDeque()
    private var pumpJob: Job? = null
    private var timerJob: Job? = null
    private var closed: Boolean = false

    // ── Public API ─────────────────────────────────────────────

    @JvmOverloads
    public fun track(
        event: StandardEvent,
        userId: String,
        properties: Map<String, Any?>? = null,
        timestamp: Instant? = null,
        messageId: String? = null,
    ) {
        track(event.eventName, userId, properties, timestamp, messageId)
    }

    @JvmOverloads
    public fun track(
        event: String,
        userId: String,
        properties: Map<String, Any?>? = null,
        timestamp: Instant? = null,
        messageId: String? = null,
    ) {
        if (options.disabled) return
        if (event.isEmpty() || userId.isEmpty()) {
            options.onError(
                TransportError("ChurnLoop.track requires `event` and `userId`"),
                1,
            )
            return
        }
        val payload = buildPayload(
            type = "track",
            event = event,
            userId = userId,
            properties = properties,
            traits = null,
            timestamp = timestamp,
            messageId = messageId,
        )
        enqueue(payload)
    }

    @JvmOverloads
    public fun identify(
        userId: String,
        traits: Map<String, Any?>? = null,
        timestamp: Instant? = null,
        messageId: String? = null,
    ) {
        if (options.disabled) return
        if (userId.isEmpty()) {
            options.onError(
                TransportError("ChurnLoop.identify requires `userId`"),
                1,
            )
            return
        }
        val payload = buildPayload(
            type = "identify",
            event = null,
            userId = userId,
            properties = null,
            traits = traits ?: emptyMap(),
            timestamp = timestamp,
            messageId = messageId,
        )
        enqueue(payload)
    }

    @JvmOverloads
    public fun page(
        userId: String,
        name: String? = null,
        properties: Map<String, Any?>? = null,
        timestamp: Instant? = null,
        messageId: String? = null,
    ) {
        enqueuePageOrScreen("page", userId, name, properties, timestamp, messageId)
    }

    @JvmOverloads
    public fun screen(
        userId: String,
        name: String? = null,
        properties: Map<String, Any?>? = null,
        timestamp: Instant? = null,
        messageId: String? = null,
    ) {
        enqueuePageOrScreen("screen", userId, name, properties, timestamp, messageId)
    }

    /**
     * Force-flush the queue. Returns when every event buffered AT
     * CALL TIME has either succeeded or been dropped (4xx /
     * exhausted retries).
     */
    public suspend fun flush() {
        cancelTimer()
        pump()
    }

    /**
     * Drain everything in the queue and stop accepting new events.
     * Suitable for shutdown hooks. Idempotent — calling close twice
     * is a no-op after the first.
     */
    public suspend fun close() {
        val wasAlreadyClosed: Boolean = synchronized(lock) {
            if (closed) return@synchronized true
            closed = true
            false
        }
        if (wasAlreadyClosed) return
        cancelTimer()
        pump()
        scope.cancel()
    }

    /** Blocking variant of [close] for Java callers and non-coroutine teardown hooks. */
    public fun closeBlocking() {
        runBlocking { close() }
    }

    // ── Internals ──────────────────────────────────────────────

    private fun enqueuePageOrScreen(
        type: String,
        userId: String,
        name: String?,
        properties: Map<String, Any?>?,
        timestamp: Instant?,
        messageId: String?,
    ) {
        if (options.disabled) return
        if (userId.isEmpty()) {
            options.onError(
                TransportError("ChurnLoop.$type requires `userId`"),
                1,
            )
            return
        }
        val props = LinkedHashMap<String, Any?>()
        properties?.forEach { (k, v) -> props[k] = v }
        if (name != null) props["name"] = name

        val payload = buildPayload(
            type = type,
            event = null,
            userId = userId,
            properties = props,
            traits = null,
            timestamp = timestamp,
            messageId = messageId,
        )
        enqueue(payload)
    }

    private enum class EnqueueOutcome { START_PUMP, ARM_TIMER, NOTHING }

    private fun enqueue(payload: JsonValue.Obj) {
        var overflowed = false
        val outcome: EnqueueOutcome = synchronized(lock) {
            if (closed) return@synchronized EnqueueOutcome.NOTHING
            if (queue.size >= options.maxQueueSize) {
                queue.removeFirst()
                overflowed = true
            }
            queue.addLast(payload)
            when {
                queue.size >= options.flushAt -> EnqueueOutcome.START_PUMP
                timerJob == null -> EnqueueOutcome.ARM_TIMER
                else -> EnqueueOutcome.NOTHING
            }
        }
        // Side effects post-lock so we never call into customer
        // closures (onError) or launch coroutines while holding it.
        if (overflowed) {
            options.onError(
                TransportError(
                    "[ChurnLoop] queue overflow (>${options.maxQueueSize}); dropping oldest event"
                ),
                1,
            )
        }
        when (outcome) {
            EnqueueOutcome.START_PUMP -> {
                cancelTimerBlocking()
                startPumpIfIdle()
            }
            EnqueueOutcome.ARM_TIMER -> armTimer()
            EnqueueOutcome.NOTHING -> Unit
        }
    }

    private fun armTimer() {
        val interval = options.flushIntervalMs
        val newTimer = scope.launch {
            delay(interval)
            timerFired()
        }
        synchronized(lock) {
            if (timerJob == null) {
                timerJob = newTimer
            } else {
                // Race: another enqueue armed the timer just before
                // we got here. Cancel ours to avoid double-firing.
                newTimer.cancel()
            }
        }
    }

    private suspend fun cancelTimer() {
        val t = synchronized(lock) {
            val current = timerJob
            timerJob = null
            current
        }
        t?.cancel()
    }

    private fun cancelTimerBlocking() {
        val t = synchronized(lock) {
            val current = timerJob
            timerJob = null
            current
        }
        t?.cancel()
    }

    private fun timerFired() {
        synchronized(lock) { timerJob = null }
        startPumpIfIdle()
    }

    private fun startPumpIfIdle() {
        synchronized(lock) {
            if (pumpJob == null) {
                pumpJob = scope.launch { pumpLoop() }
            }
        }
    }

    private suspend fun pump() {
        startPumpIfIdle()
        val t = synchronized(lock) { pumpJob }
        t?.join()
    }

    private suspend fun pumpLoop() {
        while (true) {
            val batch: List<JsonValue.Obj> = synchronized(lock) {
                val take = minOf(queue.size, options.flushAt)
                if (take <= 0) return@synchronized emptyList()
                val prefix = ArrayList<JsonValue.Obj>(take)
                repeat(take) { prefix += queue.removeFirst() }
                prefix
            }
            if (batch.isEmpty()) break
            sendWithRetry(batch)
        }
        // Clear the pumpJob ref so the NEXT trigger (size / time /
        // flush) starts a fresh pump.
        synchronized(lock) { pumpJob = null }
    }

    private suspend fun sendWithRetry(batch: List<JsonValue.Obj>) {
        val bodyJson = JsonValue.Obj(
            linkedMapOf<String, JsonValue>("batch" to JsonValue.Arr(batch))
        ).encodeToJsonString()
        var attempt = 0
        while (true) {
            try {
                transport.sendBatch(bodyJson, options)
                return
            } catch (err: Throwable) {
                val transportErr = err as? TransportError
                if (transportErr == null || !transportErr.retryable) {
                    options.onError(err, batch.size)
                    return
                }
                attempt += 1
                if (attempt >= options.maxRetries) {
                    options.onError(
                        TransportError(
                            "[ChurnLoop] dropping ${batch.size} events after $attempt retries: ${transportErr.message}",
                            statusCode = transportErr.statusCode,
                            retryable = false,
                        ),
                        batch.size,
                    )
                    return
                }
                delay(backoffMs(attempt))
            }
        }
    }

    private fun buildPayload(
        type: String,
        event: String?,
        userId: String,
        properties: Map<String, Any?>?,
        traits: Map<String, Any?>?,
        timestamp: Instant?,
        messageId: String?,
    ): JsonValue.Obj {
        val payload = LinkedHashMap<String, JsonValue>()
        payload["type"] = JsonValue.Str(type)
        if (event != null) payload["event"] = JsonValue.Str(event)
        payload["userId"] = JsonValue.Str(userId)
        payload["messageId"] = JsonValue.Str(messageId ?: UUID.randomUUID().toString())
        timestamp?.let { payload["timestamp"] = JsonValue.Str(it.toString()) }

        // $context lives in properties on every payload type
        // (matches the JS + Swift SDKs).
        val propsMap = LinkedHashMap<String, JsonValue>()
        properties?.forEach { (k, v) -> propsMap[k] = encodeAny(v) }
        propsMap["\$context"] = JvmContextBuilder.build()
        payload["properties"] = JsonValue.Obj(propsMap)

        if (traits != null) {
            val traitsMap = LinkedHashMap<String, JsonValue>()
            traits.forEach { (k, v) -> traitsMap[k] = encodeAny(v) }
            payload["traits"] = JsonValue.Obj(traitsMap)
        }

        return JsonValue.Obj(payload)
    }
}
