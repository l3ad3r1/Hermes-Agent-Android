package com.hermes.agent.domain.tool

import com.hermes.agent.data.llm.ToolCall
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolConfirmationServiceTest {

    @Test
    fun `concurrent confirmations are queued instead of overwriting each other`() = runTest {
        val service = ToolConfirmationService()
        val firstCall = ToolCall("first", "calendar_add_event", emptyMap())
        val secondCall = ToolCall("second", "device_settings", emptyMap())

        val first = async { service.awaitConfirmation(firstCall) }
        runCurrent()
        assertEquals(firstCall, service.pendingRequest.value?.call)

        val second = async { service.awaitConfirmation(secondCall) }
        runCurrent()
        assertEquals(
            "the second request must wait its turn",
            firstCall,
            service.pendingRequest.value?.call,
        )

        service.submitConfirmation(service.pendingRequest.value!!.id, true)
        assertTrue(first.await())
        runCurrent()
        assertEquals(secondCall, service.pendingRequest.value?.call)

        service.submitConfirmation(service.pendingRequest.value!!.id, false)
        assertFalse(second.await())
        assertNull(service.pendingRequest.value)
    }

    @Test
    fun `a stale request id cannot answer a newer request`() = runTest {
        val service = ToolConfirmationService()
        val firstCall = ToolCall("first", "calendar_add_event", emptyMap())
        val secondCall = ToolCall("second", "shell", emptyMap())

        val first = async { service.awaitConfirmation(firstCall) }
        runCurrent()
        val staleId = service.pendingRequest.value!!.id

        service.submitConfirmation(staleId, false)
        assertFalse(first.await())
        runCurrent()

        val second = async { service.awaitConfirmation(secondCall) }
        runCurrent()
        assertEquals(secondCall, service.pendingRequest.value?.call)

        // A verdict addressed to the dismissed dialog must not approve the
        // newer, different call (D9).
        service.submitConfirmation(staleId, true)
        runCurrent()
        assertEquals(
            "the newer request must still be pending",
            secondCall,
            service.pendingRequest.value?.call,
        )

        service.submitConfirmation(service.pendingRequest.value!!.id, true)
        assertTrue(second.await())
        assertNull(service.pendingRequest.value)
    }
}
