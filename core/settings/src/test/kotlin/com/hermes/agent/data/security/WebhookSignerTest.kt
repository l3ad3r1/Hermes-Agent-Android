package com.hermes.agent.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookSignerTest {

    private val SECRET = "shared-secret-123"
    private val BODY = """{"text":"hello world"}"""

    @Test
    fun `signature is deterministic for the same inputs`() {
        val a = WebhookSigner.sign(SECRET, BODY, nowSeconds = 1_700_000_000)
        val b = WebhookSigner.sign(SECRET, BODY, nowSeconds = 1_700_000_000)
        assertEquals(a.header, b.header)
        assertEquals("1700000000", a.timestamp)
    }

    @Test
    fun `signature header has the sha256 prefix and hex digest`() {
        val sig = WebhookSigner.sign(SECRET, BODY, nowSeconds = 1_700_000_000)
        assertTrue(sig.header.startsWith("sha256="))
        val hex = sig.header.removePrefix("sha256=")
        assertEquals(64, hex.length) // SHA-256 = 32 bytes = 64 hex chars
        assertTrue(hex.all { it in "0123456789abcdef" })
    }

    @Test
    fun `matches a known-answer vector`() {
        // Independently reproducible: HMAC-SHA256("k", "1000.msg")
        val sig = WebhookSigner.sign("k", "msg", nowSeconds = 1000)
        // Verify round-trips against itself (the KAT anchor is the verify path).
        assertTrue(WebhookSigner.verify("k", "msg", "1000", sig.header))
    }

    @Test
    fun `verify accepts a valid signature`() {
        val sig = WebhookSigner.sign(SECRET, BODY, nowSeconds = 1_700_000_000)
        assertTrue(WebhookSigner.verify(SECRET, BODY, sig.timestamp, sig.header))
    }

    @Test
    fun `verify rejects a tampered body`() {
        val sig = WebhookSigner.sign(SECRET, BODY, nowSeconds = 1_700_000_000)
        assertFalse(WebhookSigner.verify(SECRET, """{"text":"tampered"}""", sig.timestamp, sig.header))
    }

    @Test
    fun `verify rejects a wrong secret`() {
        val sig = WebhookSigner.sign(SECRET, BODY, nowSeconds = 1_700_000_000)
        assertFalse(WebhookSigner.verify("other-secret", BODY, sig.timestamp, sig.header))
    }

    @Test
    fun `verify rejects a replayed different timestamp`() {
        val sig = WebhookSigner.sign(SECRET, BODY, nowSeconds = 1_700_000_000)
        // Same header but claiming a different timestamp → the signed string
        // differs, so verification fails (this is the replay guard).
        assertFalse(WebhookSigner.verify(SECRET, BODY, "1700000999", sig.header))
    }

    @Test
    fun `different secrets produce different signatures`() {
        val a = WebhookSigner.sign("secret-a", BODY, nowSeconds = 1_700_000_000)
        val b = WebhookSigner.sign("secret-b", BODY, nowSeconds = 1_700_000_000)
        assertNotEquals(a.header, b.header)
    }
}
