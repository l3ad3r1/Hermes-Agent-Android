package com.hermes.agent.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaVersionTest {

    @Test
    fun `a debug suffix does not make an older release look newer`() {
        // On the tablet 1.0.8-debug was offered "1.0.4 is available!".
        assertFalse(isNewerVersion("1.0.4", "1.0.8-debug"))
        assertFalse(isNewerVersion("1.0.8", "1.0.8-debug"))
        assertTrue(isNewerVersion("1.0.9", "1.0.8-debug"))
    }

    @Test
    fun `plain versions compare numerically`() {
        assertTrue(isNewerVersion("1.0.10", "1.0.9"))
        assertFalse(isNewerVersion("1.0.4", "1.0.4"))
        assertTrue(isNewerVersion("1.1", "1.0.9"))
    }
}
