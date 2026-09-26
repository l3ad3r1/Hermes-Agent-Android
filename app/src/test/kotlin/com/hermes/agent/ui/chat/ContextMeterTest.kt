package com.hermes.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextMeterTest {
    @Test
    fun `formats sizes compactly`() {
        assertEquals("950", ContextMeter.format(950))
        assertEquals("4.3K", ContextMeter.format(4_300))
        assertEquals("32K", ContextMeter.format(32_768))
        assertEquals("200K", ContextMeter.format(200_000))
        assertEquals("1M", ContextMeter.format(1_000_000))
    }

    @Test
    fun `known model families get their window and unknown ones do not`() {
        assertEquals(200_000, ContextMeter.windowFor("claude-sonnet-5"))
        assertEquals(128_000, ContextMeter.windowFor("gpt-4o-mini"))
        assertEquals(1_000_000, ContextMeter.windowFor("gemini-2.5-pro"))
        assertNull(ContextMeter.windowFor("my-private-model"))
    }

    @Test
    fun `label shows the window only when it is known and nothing for an empty chat`() {
        assertEquals("~4.3K / 200K tokens", ContextMeter.label(4_300, "claude-sonnet-5"))
        assertEquals("~4.3K tokens", ContextMeter.label(4_300, "my-private-model"))
        assertEquals("", ContextMeter.label(0, "claude-sonnet-5"))
    }
}
