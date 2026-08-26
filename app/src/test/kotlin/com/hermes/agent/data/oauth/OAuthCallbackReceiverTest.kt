package com.hermes.agent.data.oauth

import android.net.Uri
import com.hermes.agent.domain.oauth.OAuthSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthCallbackReceiverTest {

    @Test
    fun `valid callback with matching state emits Success`() = runTest {
        val receiver = OAuthCallbackReceiver()
        val session = OAuthSession(
            providerId = "openrouter",
            state = "valid-state-123",
            codeVerifier = "verifier",
            redirectUri = "hermes://oauth/callback",
        )
        receiver.registerPendingSession(session)

        val uri = mockk<Uri> {
            every { getQueryParameter("error") } returns null
            every { getQueryParameter("error_description") } returns null
            every { getQueryParameter("code") } returns "auth-code-xyz"
            every { getQueryParameter("state") } returns "valid-state-123"
        }

        receiver.handleCallback(uri)

        val event = receiver.events.first()
        assertTrue(event is OAuthCallbackEvent.Success)
        val success = event as OAuthCallbackEvent.Success
        assertEquals("openrouter", success.session.providerId)
        assertEquals("auth-code-xyz", success.code)
    }

    @Test
    fun `callback with error parameter emits Error`() = runTest {
        val receiver = OAuthCallbackReceiver()
        val session = OAuthSession(
            providerId = "nous",
            state = "valid-state-123",
            codeVerifier = "verifier",
            redirectUri = "hermes://oauth/callback",
        )
        receiver.registerPendingSession(session)

        val uri = mockk<Uri> {
            every { getQueryParameter("error") } returns "access_denied"
            every { getQueryParameter("error_description") } returns null
        }

        receiver.handleCallback(uri)

        val event = receiver.events.first()
        assertTrue(event is OAuthCallbackEvent.Error)
        val error = event as OAuthCallbackEvent.Error
        assertEquals("access_denied", error.error)
    }

    @Test
    fun `callback with state mismatch emits Error`() = runTest {
        val receiver = OAuthCallbackReceiver()
        val session = OAuthSession(
            providerId = "openrouter",
            state = "valid-state-123",
            codeVerifier = "verifier",
            redirectUri = "hermes://oauth/callback",
        )
        receiver.registerPendingSession(session)

        val uri = mockk<Uri> {
            every { getQueryParameter("error") } returns null
            every { getQueryParameter("error_description") } returns null
            every { getQueryParameter("code") } returns "auth-code-xyz"
            every { getQueryParameter("state") } returns "malicious-state-456"
        }

        receiver.handleCallback(uri)

        val event = receiver.events.first()
        assertTrue(event is OAuthCallbackEvent.Error)
        val error = event as OAuthCallbackEvent.Error
        assertTrue(error.error.contains("State mismatch"))
    }
}
