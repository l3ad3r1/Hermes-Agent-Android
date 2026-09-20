package com.hermes.agent.data.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningTraceTest {

    @Test
    fun `rounds without thinking add nothing, not even their time`() {
        val t = ReasoningTrace()
        t.record("", 900)
        t.record("   ", 900)
        assertEquals("", t.text())
        assertEquals(0L, t.millis)
    }

    @Test
    fun `thoughts are kept in order and only the time of the rounds that thought is counted`() {
        val t = ReasoningTrace()
        t.record("first", 2_000)
        t.record("", 5_000)
        t.record("second", 4_000)
        assertEquals("first\n\nsecond", t.text())
        assertEquals(6_000L, t.millis)
    }
}
