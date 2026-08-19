package com.hermes.agent.domain.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AgentActivity] is a process-wide singleton, so each test drains it back to
 * a resting state rather than relying on construction order.
 */
class AgentActivityTest {

    @After
    fun drain() {
        repeat(8) { AgentActivity.end() }
    }

    @Test
    fun `phase starts idle`() {
        assertEquals(AgentPhase.IDLE, AgentActivity.phase.value)
    }

    @Test
    fun `phase updates while a run is in flight`() {
        AgentActivity.begin()
        AgentActivity.setPhase(AgentPhase.SEARCHING)
        assertEquals(AgentPhase.SEARCHING, AgentActivity.phase.value)

        AgentActivity.setPhase(AgentPhase.COMPOSING)
        assertEquals(AgentPhase.COMPOSING, AgentActivity.phase.value)
    }

    @Test
    fun `phase is ignored when nothing is running`() {
        // Guards against a late callback from a finished run resurrecting the
        // orb after the bubble is gone.
        AgentActivity.setPhase(AgentPhase.WORKING)
        assertEquals(AgentPhase.IDLE, AgentActivity.phase.value)
    }

    @Test
    fun `phase resets to idle only when the last run ends`() {
        AgentActivity.begin()
        AgentActivity.begin()
        AgentActivity.setPhase(AgentPhase.THINKING)

        AgentActivity.end()
        assertEquals(
            "one run is still in flight, so the phase must survive",
            AgentPhase.THINKING,
            AgentActivity.phase.value,
        )

        AgentActivity.end()
        assertEquals(AgentPhase.IDLE, AgentActivity.phase.value)
    }

    @Test
    fun `end never drives the counter negative`() {
        AgentActivity.end()
        AgentActivity.end()
        AgentActivity.begin()
        assertEquals(1, AgentActivity.activeRuns.value)
    }
}
