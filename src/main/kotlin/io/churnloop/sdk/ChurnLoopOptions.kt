package io.churnloop.sdk

/**
 * Hook called when a non-fatal SDK error occurs (failed send,
 * dropped event). Receives the error and the number of events
 * dropped by this failure.
 */
public typealias ChurnLoopErrorHandler = (error: Throwable, droppedEvents: Int) -> Unit

/**
 * Configuration for a [ChurnLoop] instance.
 *
 * `apiKey` is the only required field. Defaults match the JS + Swift
 * SDKs where they exist.
 */
public data class ChurnLoopOptions(
    /**
     * Bearer token from the ChurnLoop API-keys page (starts with `cl_`).
     * On Android, embed via your configuration of choice (build
     * variants, remote config, secure storage) — never hardcode.
     */
    val apiKey: String,

    /**
     * Ingest host. Default: the configured production host.
     * Override for self-hosted or staging environments.
     */
    val host: String = DEFAULT_HOST,

    /** Per-request timeout in milliseconds. Default 30 000. */
    val timeoutMs: Int = 30_000,

    /**
     * When `true`, all calls are no-ops. Useful for unit tests in
     * app code, dev builds, or consent-gated mode.
     */
    val disabled: Boolean = false,

    // ── v0.2 batching + retry ──────────────────────────────────

    /**
     * Flush the queue once it reaches this many buffered events.
     * Default 100 (matches the server's Segment-batch endpoint
     * cap). Higher values are rejected by the server.
     */
    val flushAt: Int = 100,

    /**
     * Flush the queue if events have been sitting longer than
     * this many milliseconds since the first buffered event.
     * Default 1000. Trade-off: lower = more requests, fresher
     * data; higher = fewer requests, more buffered delay.
     */
    val flushIntervalMs: Long = 1000L,

    /**
     * Hard cap on the in-memory queue. When exceeded, the OLDEST
     * events are dropped (with the `onError` callback called).
     * Default 1000.
     */
    val maxQueueSize: Int = 1000,

    /**
     * Maximum retry attempts on retryable failures (network
     * errors, 429, 5xx). Each attempt waits an exponential
     * backoff with full jitter (250ms base, capped at 30s).
     * Default 5.
     */
    val maxRetries: Int = 5,

    /**
     * Called when a non-fatal error occurs. Defaults to printing
     * to stderr so dropped events surface during development.
     * Production apps should provide a real handler that routes
     * to your crash reporter / structured logger.
     */
    val onError: ChurnLoopErrorHandler = { error, dropped ->
        System.err.println("[ChurnLoop] ${error.message} (dropped: $dropped)")
    },
) {
    public companion object {
        public const val DEFAULT_HOST: String = "https://ingest.churnloop.com"
    }
}
