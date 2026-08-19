package com.hermes.agent.domain.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTerminalBackendTest {

    private fun config(
        host: String = "example.com",
        port: Int = 22,
        user: String = "root",
    ) = RemoteTerminalBackend.Config(host = host, port = port, username = user, password = "pw")

    @Test
    fun `fully specified config is configured`() {
        assertTrue(config().isConfigured)
    }

    @Test
    fun `blank host is not configured`() {
        assertFalse(config(host = "").isConfigured)
        assertFalse(config(host = "   ").isConfigured) // whitespace-only host
    }

    @Test
    fun `blank user is not configured`() {
        assertFalse(config(user = "").isConfigured)
    }

    @Test
    fun `out-of-range ports are not configured`() {
        assertFalse(config(port = 0).isConfigured)
        assertFalse(config(port = 70000).isConfigured)
        assertFalse(config(port = -1).isConfigured)
    }

    @Test
    fun `boundary ports are configured`() {
        assertTrue(config(port = 1).isConfigured)
        assertTrue(config(port = 65535).isConfigured)
    }
}
