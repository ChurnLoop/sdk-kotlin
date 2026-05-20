package io.churnloop.sdk

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebhookVerifyTest {
    private val secret = "whsec_test_some_secret_value"
    private val nowEpoch: Long = 1_780_000_000L
    private val clock: Clock = Clock.fixed(Instant.ofEpochSecond(nowEpoch), ZoneOffset.UTC)

    /**
     * Build a valid ChurnLoop-shape signature header using the same
     * algorithm the server uses. Symmetric to the SDK's verifier
     * implementation but written here independently — so a bug in
     * the SDK's HMAC code couldn't accidentally hide a bug in the
     * test fixture.
     */
    private fun sign(rawBody: String, secret: String, timestamp: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("$timestamp.$rawBody".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "t=$timestamp,v1=$hex"
    }

    // ── Happy path ─────────────────────────────────────────────

    @Test
    fun `accepts freshly signed webhook`() {
        val body = """{"type":"intervention.executed","data":{"id":"i_1"}}"""
        val header = sign(body, secret, nowEpoch)

        val result = WebhookVerify.verify(body, header, secret, clock = clock)

        assertTrue(result.isValid)
    }

    @Test
    fun `accepts signature within default tolerance window`() {
        // Signed 4 minutes ago — under the 5 min default tolerance.
        val body = "{}"
        val header = sign(body, secret, nowEpoch - 4 * 60)

        val result = WebhookVerify.verify(body, header, secret, clock = clock)

        assertTrue(result.isValid)
    }

    // ── Replay protection ──────────────────────────────────────

    @Test
    fun `rejects timestamp older than tolerance`() {
        // 10 min old > 5 min default — likely a replayed capture.
        val body = "{}"
        val header = sign(body, secret, nowEpoch - 10 * 60)

        val result = WebhookVerify.verify(body, header, secret, clock = clock)

        assertEquals(
            VerifyResult.Invalid(VerifyFailureReason.TIMESTAMP_OUT_OF_RANGE),
            result,
        )
    }

    @Test
    fun `rejects future-dated timestamp`() {
        // Clock skew or attacker pre-signing.
        val body = "{}"
        val header = sign(body, secret, nowEpoch + 10 * 60)

        val result = WebhookVerify.verify(body, header, secret, clock = clock)

        assertEquals(
            VerifyResult.Invalid(VerifyFailureReason.TIMESTAMP_OUT_OF_RANGE),
            result,
        )
    }

    @Test
    fun `respects custom tolerance override`() {
        // Behind a slow queue: widen the window to 30 minutes;
        // signature signed 20 min ago is now valid.
        val body = "{}"
        val header = sign(body, secret, nowEpoch - 20 * 60)

        val result = WebhookVerify.verify(
            rawBody = body,
            signatureHeader = header,
            secret = secret,
            toleranceSeconds = 30 * 60,
            clock = clock,
        )

        assertTrue(result.isValid)
    }

    // ── Signature validation ───────────────────────────────────

    @Test
    fun `rejects tampered body`() {
        val body = """{"amount":100}"""
        val header = sign(body, secret, nowEpoch)
        val tampered = """{"amount":10000}"""

        val result = WebhookVerify.verify(tampered, header, secret, clock = clock)

        assertEquals(
            VerifyResult.Invalid(VerifyFailureReason.SIGNATURE_MISMATCH),
            result,
        )
    }

    @Test
    fun `rejects wrong secret`() {
        val body = "{}"
        val header = sign(body, secret, nowEpoch)

        val result = WebhookVerify.verify(body, header, "whsec_wrong_secret", clock = clock)

        assertEquals(
            VerifyResult.Invalid(VerifyFailureReason.SIGNATURE_MISMATCH),
            result,
        )
    }

    // ── Malformed input ────────────────────────────────────────

    @Test
    fun `rejects empty header`() {
        val result = WebhookVerify.verify("{}", "", secret, clock = clock)
        assertEquals(
            VerifyResult.Invalid(VerifyFailureReason.MALFORMED_SIGNATURE),
            result,
        )
    }

    @Test
    fun `rejects header missing timestamp`() {
        val result = WebhookVerify.verify("{}", "v1=abcdef", secret, clock = clock)
        assertEquals(
            VerifyResult.Invalid(VerifyFailureReason.MALFORMED_SIGNATURE),
            result,
        )
    }

    @Test
    fun `rejects header missing v1 entry`() {
        val result = WebhookVerify.verify("{}", "t=$nowEpoch", secret, clock = clock)
        assertEquals(
            VerifyResult.Invalid(VerifyFailureReason.MALFORMED_SIGNATURE),
            result,
        )
    }

    @Test
    fun `rejects non-hex v1 value`() {
        val result = WebhookVerify.verify(
            "{}",
            "t=$nowEpoch,v1=not-hex-at-all",
            secret,
            clock = clock,
        )
        assertEquals(
            VerifyResult.Invalid(VerifyFailureReason.MALFORMED_SIGNATURE),
            result,
        )
    }

    @Test
    fun `rejects non-numeric timestamp`() {
        val result = WebhookVerify.verify(
            "{}",
            "t=not-a-number,v1=abcdef",
            secret,
            clock = clock,
        )
        assertEquals(
            VerifyResult.Invalid(VerifyFailureReason.MALFORMED_SIGNATURE),
            result,
        )
    }

    @Test
    fun `tolerates whitespace around separators`() {
        // Defensive: some proxies / gateways re-serialise headers
        // with extra whitespace. The verifier accepts that.
        val body = "{}"
        val signed = sign(body, secret, nowEpoch)
        val spaced = signed.replace(",", " , ")

        val result = WebhookVerify.verify(body, spaced, secret, clock = clock)

        assertTrue(result.isValid)
    }

    @Test
    fun `tolerates future signature scheme in same header`() {
        // Forward-compat — if the server ever emits v2 in the same
        // header, older SDKs still verify against v1 and accept.
        val body = "{}"
        val signed = sign(body, secret, nowEpoch)
        val forwardCompat = "$signed,v2=futurealgorithm"

        val result = WebhookVerify.verify(body, forwardCompat, secret, clock = clock)

        assertTrue(result.isValid)
    }
}
