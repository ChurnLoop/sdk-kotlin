package io.churnloop.sdk

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Error surfaced by the transport layer. Mirrors `TransportError`
 * in the JS + Swift SDKs so cross-platform docs stay consistent.
 */
public class TransportError(
    message: String,
    public val statusCode: Int? = null,
    public val retryable: Boolean = false,
) : RuntimeException(message)

/**
 * v0.2 transport: POSTs a `{ "batch": [...] }` envelope to
 * /v1/compat/segment/batch. The interface exists so tests can inject
 * a stub without standing up a real HTTP server.
 *
 * `bodyJson` is the already-serialised envelope — building it lives
 * in [ChurnLoop] so the transport stays pure I/O.
 */
public interface Transport {
    public fun sendBatch(bodyJson: String, options: ChurnLoopOptions)
}

/**
 * Default transport using [HttpURLConnection] (JDK built-in) so the
 * SDK stays dependency-free. OkHttp would add ~700 KB to the
 * Android binary; for the SDK's modest request rate the difference
 * vs OkHttp is undetectable.
 */
public class HttpUrlConnectionTransport : Transport {
    override fun sendBatch(bodyJson: String, options: ChurnLoopOptions) {
        val endpoint = stripTrailingSlash(options.host) + "/v1/compat/segment/batch"
        val url: URL = URI.create(endpoint).toURL()

        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = options.timeoutMs
            connection.readTimeout = options.timeoutMs
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${options.apiKey}")

            val bytes = bodyJson.toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val status: Int = try {
                connection.responseCode
            } catch (e: IOException) {
                // Network failure / DNS / timeout. Surface as
                // retryable so the retry layer can back off.
                throw TransportError(
                    "ChurnLoop ingest unreachable: ${e.message}",
                    statusCode = null,
                    retryable = true,
                )
            }

            if (status in 200..299) return

            // 429 + 5xx are transient (retryable); 4xx is permanent
            // (caller bug, no point retrying).
            val retryable = status == 429 || status >= 500
            val detail = readErrorBody(connection) ?: "HTTP $status"
            throw TransportError(
                "ChurnLoop ingest rejected batch ($status): $detail",
                statusCode = status,
                retryable = retryable,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readErrorBody(connection: HttpURLConnection): String? {
        val stream = connection.errorStream ?: return null
        val body = stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        return extractMessageField(body)
    }

    private fun extractMessageField(body: String): String? {
        val key = "\"message\""
        val keyIndex = body.indexOf(key)
        if (keyIndex < 0) return body.take(200).ifEmpty { null }
        var i = keyIndex + key.length
        while (i < body.length && (body[i].isWhitespace() || body[i] == ':')) i++
        if (i >= body.length) return null
        return when (body[i]) {
            '"' -> readJsonString(body, i)
            '[' -> readJsonArrayJoined(body, i)
            else -> body.substring(i).take(200)
        }
    }

    private fun readJsonString(body: String, start: Int): String? {
        if (start >= body.length || body[start] != '"') return null
        val sb = StringBuilder()
        var i = start + 1
        while (i < body.length) {
            val ch = body[i]
            if (ch == '"') return sb.toString()
            if (ch == '\\' && i + 1 < body.length) {
                sb.append(body[i + 1])
                i += 2
                continue
            }
            sb.append(ch)
            i++
        }
        return null
    }

    private fun readJsonArrayJoined(body: String, start: Int): String? {
        if (start >= body.length || body[start] != '[') return null
        val out = mutableListOf<String>()
        var i = start + 1
        while (i < body.length) {
            val ch = body[i]
            when {
                ch == ']' -> return out.joinToString("; ")
                ch == '"' -> {
                    val s = readJsonString(body, i) ?: return null
                    out += s
                    i += s.length + 2
                }
                else -> i++
            }
        }
        return null
    }
}

private fun stripTrailingSlash(s: String): String =
    if (s.endsWith('/')) s.dropLast(1) else s

// ── Backoff ───────────────────────────────────────────────────────

/**
 * Base + cap for exponential backoff. Matches the JS + Swift SDKs
 * so retry timing is uniform across platforms — a tenant under load
 * from multiple SDK runtimes spreads retries on the same schedule.
 */
private const val BACKOFF_BASE_MS: Double = 250.0
private const val BACKOFF_MAX_MS: Double = 30_000.0

/**
 * Compute the next backoff delay (ms) for retry `attempt` (1-indexed)
 * with FULL JITTER — uniform random in `[0, base * 2^(attempt-1)]`,
 * capped at [BACKOFF_MAX_MS].
 *
 * Full jitter is critical: without it, every SDK retrying the same
 * 503 hits the server at identical instants. Random spreading
 * prevents synchronised herds.
 *
 * `random` injectable for deterministic tests.
 */
public fun backoffMs(
    attempt: Int,
    random: () -> Double = { Random.nextDouble() },
): Long {
    val expCap = min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * 2.0.pow(attempt - 1))
    val drawnMs = floor(random() * expCap)
    return drawnMs.toLong()
}
