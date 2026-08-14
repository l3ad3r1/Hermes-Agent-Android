package com.hermes.agent.domain.tool

import com.hermes.agent.domain.agent.ExecutionOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

class ToolExecutionPolicyTest {

    private fun policy(trustedBackground: Boolean = false): ToolExecutionPolicy {
        val settings = mockk<ToolAuthorizationSettings>(relaxed = true)
        coEvery { settings.trustedBackgroundPhoneActions() } returns trustedBackground
        return ToolExecutionPolicy(settings)
    }

    @Test
    fun `background denies never-autonomous tools regardless of confirmation flag`() = runTest {
        val policy = policy()
        for (tool in ToolExecutionPolicy.NEVER_AUTONOMOUS) {
            val decision = policy.evaluate(ExecutionOrigin.BACKGROUND, tool, requiresConfirmation = false)
            assertTrue("$tool must be denied in background", decision is ToolExecutionDecision.Deny)
            assertTrue(
                "denial must name the tool and the fix",
                (decision as ToolExecutionDecision.Deny).reason.contains(tool),
            )
        }
    }

    @Test
    fun `background denies confirmation-required tools instead of waiting on a dialog`() = runTest {
        val policy = policy()
        val decision = policy.evaluate(
            ExecutionOrigin.BACKGROUND,
            "calendar_add_event",
            requiresConfirmation = true,
        )
        assertTrue(decision is ToolExecutionDecision.Deny)
    }

    @Test
    fun `background allows ordinary tools`() = runTest {
        val policy = policy()
        assertEquals(
            ToolExecutionDecision.Allow,
            policy.evaluate(ExecutionOrigin.BACKGROUND, "search_notes", requiresConfirmation = false),
        )
    }

    @Test
    fun `interactive gates never-autonomous tools even without the descriptor flag`() = runTest {
        val policy = policy()
        assertEquals(
            ToolExecutionDecision.Confirm,
            policy.evaluate(ExecutionOrigin.INTERACTIVE, "shell", requiresConfirmation = false),
        )
    }

    @Test
    fun `app automation actions continue without replacing the target window`() = runTest {
        val policy = policy()
        for (tool in listOf("app_tap", "app_swipe", "app_type")) {
            assertTrue(
                "$tool must be denied in background",
                policy.evaluate(
                    ExecutionOrigin.BACKGROUND,
                    tool,
                    requiresConfirmation = false,
                ) is ToolExecutionDecision.Deny,
            )
            assertEquals(
                ToolExecutionDecision.Allow,
                policy.evaluate(
                    ExecutionOrigin.INTERACTIVE,
                    tool,
                    requiresConfirmation = false,
                ),
            )
        }
    }

    @Test
    fun `interactive gates confirmation-required tools`() = runTest {
        val policy = policy()
        assertEquals(
            ToolExecutionDecision.Confirm,
            policy.evaluate(ExecutionOrigin.INTERACTIVE, "calendar_add_event", requiresConfirmation = true),
        )
    }

    @Test
    fun `interactive allows ordinary tools`() = runTest {
        val policy = policy()
        assertEquals(
            ToolExecutionDecision.Allow,
            policy.evaluate(ExecutionOrigin.INTERACTIVE, "search_notes", requiresConfirmation = false),
        )
    }

    @Test
    fun `authenticated trusted mode allows only the background-safe phone subset`() = runTest {
        val policy = policy(trustedBackground = true)

        for (tool in ToolExecutionPolicy.TRUSTED_BACKGROUND_TOOLS) {
            assertEquals(
                ToolExecutionDecision.Allow,
                policy.evaluate(ExecutionOrigin.BACKGROUND, tool, requiresConfirmation = true),
            )
        }
        assertTrue(
            policy.evaluate(ExecutionOrigin.BACKGROUND, "shell", requiresConfirmation = true) is
                ToolExecutionDecision.Deny,
        )
        assertTrue(
            policy.evaluate(ExecutionOrigin.BACKGROUND, "app_launch", requiresConfirmation = true) is
                ToolExecutionDecision.Deny,
        )
    }
}
