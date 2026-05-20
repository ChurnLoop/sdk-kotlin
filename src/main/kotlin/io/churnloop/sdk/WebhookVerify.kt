package io.churnloop.sdk

import java.time.Clock
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Reasons [WebhookVerify.verify] can return [VerifyResult.Invalid].
 * Closed enum so callers can switch exhaustively when logging.
 */
public enum class VerifyFailureReason {
    /** Header doesn't match the `t=...,v1=...` shape. */
    MALFORMED_SIGNATURE,

    /** Signed timestamp is outside the tolerance window — likely
     *  a replay attempt or extreme clock skew. */
    TIMESTAMP_OUT_OF_RANGE,

    /** HMAC didn't match — body tampered or wrong secret. */
    SIGNATURE_MISMATCH,
}

/** Result of a webhook-verification call. */
public sealed class VerifyResult {
    public object Valid : VerifyResult()
    public data class Invalid(val reason: VerifyFailureReason) : VerifyResult()

    public val isValid: Boolean get() = this is Valid
}

/**
 * Inbound-webhook signature verifier. Matches the JS + Swift SDKs
 * byte-for-byte:
 *
 * - Header: `X-ChurnLoop-Signature: t=<unix>,v1=<hex>`
 * - Algorithm: HMAC-SHA256 over `${timestamp}.${rawBody}`
 * - Default tolerance: 300 seconds (matches Stripe + the server)
 * - Constant-time hex comparison
 */
public object WebhookVerify {
    public const val DEFAULT_TOLERANCE_SECONDS: Long = 300L

    /**
     * Verify a ChurnLoop webhook signature.
     *
     * @param rawBody byte-exact request body as a String (UTF-8).
     *   MUST be unmodified — re-serialisation breaks the
     *   signature. Read from the raw input stream BEFORE decoding
     *   to JSON.
     * @param signatureHeader value of `X-ChurnLoop-Signature`
     *   verbatim.
     * @param secret webhook secret from your ChurnLoop tenant
     *   settings.
     * @param toleranceSeconds allowed clock skew. Default 300.
     * @param clock injected for testing. In production use the
     *   default (system UTC clock).
     */
    @JvmOverloads
    public fun verify(
        rawBody: String,
        signatureHeader: String,
        secret: String,
        toleranceSeconds: Long = DEFAULT_TOLERANCE_SECONDS,
        clock: Clock = Clock.systemUTC(),
    ): VerifyResult {
        val parsed = parseSignatureHeader(signatureHeader)
            ?: return VerifyResult.Invalid(VerifyFailureReason.MALFORMED_SIGNATURE)

        val nowSeconds = Instant.now(clock).epochSecond
        if (abs(nowSeconds - parsed.timestamp) > toleranceSeconds) {
            return VerifyResult.Invalid(VerifyFailureReason.TIMESTAMP_OUT_OF_RANGE)
        }

        val signed = "${parsed.timestamp}.$rawBody"
        val expectedHex = hmacSha256Hex(secret, signed)

        return if (constantTimeEquals(expectedHex, parsed.signature)) {
            VerifyResult.Valid
        } else {
            VerifyResult.Invalid(VerifyFailureReason.SIGNATURE_MISMATCH)
        }
    }

    internal data class ParsedSignature(val timestamp: Long, val signature: String)

    /**
     * Parse `t=<unix>,v1=<hex>` into its parts. Tolerant of
     * arbitrary ordering, extra fields (forward-compat with future
     * `v2`), and whitespace around separators.
     */
    internal fun parseSignatureHeader(header: String): ParsedSignature? {
        if (header.isEmpty()) return null
        var t: Long? = null
        var v1: String? = null
        for (rawPart in header.split(',')) {
            val part = rawPart.trim()
            val eq = part.indexOf('=')
            if (eq < 0) continue
            val key = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            when (key) {
                "t" -> {
                    val parsed = value.toLongOrNull() ?: return null
                    if (parsed <= 0) return null
                    t = parsed
                }
                "v1" -> {
                    if (value.isEmpty()) return null
                    if (!value.all { ch -> ch.isDigit() || ch in 'a'..'f' || ch in 'A'..'F' }) {
                        return null
                    }
                    v1 = value
                }
                // ignore unknown entries (forward compat)
            }
        }
        val ts = t ?: return null
        val sig = v1 ?: return null
        return ParsedSignature(ts, sig)
    }

    private fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (byte in digest) {
            sb.append("%02x".format(byte.toInt() and 0xff))
        }
        return sb.toString()
    }

    /**
     * Constant-time hex-string comparison. Standard defence
     * against timing-attack inference of the expected signature.
     * Always compares the full length — different lengths are an
     * immediate fail but the comparison loop still runs.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var mismatch = 0
        for (i in a.indices) {
            mismatch = mismatch or (a[i].code xor b[i].code)
        }
        return mismatch == 0
    }
}
