package com.hermes.agent.data.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 signing for outbound custom-webhook deliveries — ported from
 * hermes-agent's connector-channel authenticator (docs/relay-connector-
 * contract.md §6.1: "HMAC-SHA256 scheme … `sig = HMAC(payload:exp, secret)`").
 *
 * The generic WEBHOOK connector POSTs to a URL the user controls, so a
 * shared secret lets that receiver prove the request genuinely came from
 * this Hermes instance and was not tampered with or replayed. (Telegram,
 * Discord, WhatsApp, etc. authenticate via their own bot tokens and need
 * no signature — this applies only to the custom webhook.)
 *
 * Scheme (GitHub/Stripe-style, verifiable in a few lines on any backend):
 *   - `X-Hermes-Timestamp: <unix-seconds>`
 *   - `X-Hermes-Signature: sha256=<hex(HMAC-SHA256(secret, "<ts>.<body>"))>`
 * The timestamp is inside the signed string, so a receiver that rejects
 * stale timestamps gets replay protection for free.
 */
object WebhookSigner {

    const val HEADER_TIMESTAMP = "X-Hermes-Timestamp"
    const val HEADER_SIGNATURE = "X-Hermes-Signature"

    data class Signature(val timestamp: String, val header: String)

    /** Sign [body] with [secret] at [nowSeconds]. */
    fun sign(secret: String, body: String, nowSeconds: Long = System.currentTimeMillis() / 1000): Signature {
        val ts = nowSeconds.toString()
        val digest = hmacSha256Hex(secret, "$ts.$body")
        return Signature(timestamp = ts, header = "sha256=$digest")
    }

    /** Constant-time verify — for symmetry / on-device self-tests. */
    fun verify(secret: String, body: String, timestamp: String, signatureHeader: String): Boolean {
        val expected = "sha256=" + hmacSha256Hex(secret, "$timestamp.$body")
        return constantTimeEquals(expected, signatureHeader)
    }

    private fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }
}
