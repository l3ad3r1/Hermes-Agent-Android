package com.hermes.agent.data.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedShellRetryGateTest {

    @Test
    fun `starts in idle state and allows execution`() {
        val gate = PrivilegedShellRetryGate()
        assertTrue(gate.canExecute())
        assertEquals(PrivilegedShellRetryGate.State.IDLE, gate.status.value.state)
    }

    @Test
    fun `normal execution lifecycle transitions to running then back to idle`() {
        val gate = PrivilegedShellRetryGate()
        gate.onExecutionStart()
        assertEquals(PrivilegedShellRetryGate.State.RUNNING, gate.status.value.state)

        gate.onExecutionSuccess()
        assertEquals(PrivilegedShellRetryGate.State.IDLE, gate.status.value.state)
        assertTrue(gate.canExecute())
    }

    @Test
    fun `unverified unwind transitions to DIRTY_UNWIND and blocks execution until reset`() {
        val gate = PrivilegedShellRetryGate()
        gate.onExecutionStart()

        gate.onExecutionFailure(unverifiedUnwind = true, reason = "Timed out after 15s")
        assertEquals(PrivilegedShellRetryGate.State.DIRTY_UNWIND, gate.status.value.state)
        assertEquals("Timed out after 15s", gate.status.value.reason)
        assertFalse(gate.canExecute())

        // Calling reset opens the gate again
        gate.resetGate()
        assertEquals(PrivilegedShellRetryGate.State.IDLE, gate.status.value.state)
        assertTrue(gate.canExecute())
    }

    @Test
    fun `normal non-unwind failure returns to idle and does not lock gate`() {
        val gate = PrivilegedShellRetryGate()
        gate.onExecutionStart()

        gate.onExecutionFailure(unverifiedUnwind = false, reason = "Non-zero exit code")
        assertEquals(PrivilegedShellRetryGate.State.IDLE, gate.status.value.state)
        assertTrue(gate.canExecute())
    }
}
