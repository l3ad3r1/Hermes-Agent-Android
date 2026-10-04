package com.hermes.agent.data.evolution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEvidenceSanitizerTest {

    @Test
    fun `plain text passes through`() {
        assertEquals("web_search failed with HTTP 429", AppEvidenceSanitizer.sanitize("  web_search failed with HTTP 429 "))
    }

    @Test
    fun `anything carrying a credential is dropped entirely`() {
        assertNull(AppEvidenceSanitizer.sanitize("call it with sk-abcdefghijklmnopqrstuvwxyz0123456789"))
        assertNull(AppEvidenceSanitizer.sanitize("Authorization: Bearer abcdefghijklmnopqrstuvwxyz012345"))
        assertNull(AppEvidenceSanitizer.sanitize("my password: hunter22"))
        assertNull(AppEvidenceSanitizer.sanitize("ghp_abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun `personal data is redacted with the problem-report rules`() {
        val out = AppEvidenceSanitizer.sanitize("email me at jane.doe@example.com or +44 20 7946 0958 from 192.168.1.20")!!
        assertFalse(out, "jane.doe" in out)
        assertFalse(out, "7946" in out)
        assertFalse(out, "192.168" in out)
        assertTrue(out, "[email]" in out && "[number]" in out && "[ip]" in out)
    }

    @Test
    fun `blank text yields nothing`() {
        assertNull(AppEvidenceSanitizer.sanitize("   "))
    }
}
