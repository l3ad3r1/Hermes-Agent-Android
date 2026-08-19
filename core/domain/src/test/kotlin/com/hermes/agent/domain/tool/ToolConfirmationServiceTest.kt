package com.hermes.agent.domain.tool

import com.hermes.agent.domain.llm.ToolCall
import com.hermes.agent.domain.security.DeviceAuthenticationService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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

    private fun service(autoApprovePhoneActions: Boolean = false): ToolConfirmationService {
        val settings = mockk<ToolAuthorizationSettings>(relaxed = true)
        coEvery { settings.autoApprovePhoneActions() } returns autoApprovePhoneActions
        return ToolConfirmationService(settings, mockk(relaxed = true))
    }

    @Test
    fun `concurrent confirmations are queued instead of overwriting each other`() = runTest {
        val service = service()
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
        val service = service()
        val firstCall = ToolCall("first", "calendar_add_event", emptyMap())
        val secondCall = ToolCall("second", "navigation", emptyMap())

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

    @Test
    fun `opt-in auto approval covers phone tools without creating a pending request`() = runTest {
        val service = service(autoApprovePhoneActions = true)

        assertTrue(service.awaitConfirmation(ToolCall("phone", "device_control", emptyMap())))
        assertNull(service.pendingRequest.value)
    }

    @Test
    fun `shell uses device authentication even when phone auto approval is enabled`() = runTest {
        val settings = mockk<ToolAuthorizationSettings>(relaxed = true)
        coEvery { settings.autoApprovePhoneActions() } returns true
        val deviceAuth = mockk<DeviceAuthenticationService>()
        coEvery { deviceAuth.authenticate(any(), any()) } returns false
        val service = ToolConfirmationService(settings, deviceAuth)

        assertFalse(service.awaitConfirmation(ToolCall("danger", "shell", emptyMap())))
        coVerify(exactly = 1) { deviceAuth.authenticate(any(), any()) }
        assertNull(service.pendingRequest.value)
    }
}
