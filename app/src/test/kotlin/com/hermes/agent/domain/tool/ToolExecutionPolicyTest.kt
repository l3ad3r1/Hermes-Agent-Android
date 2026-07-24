package com.hermes.agent.domain.tool

import com.hermes.agent.domain.agent.ExecutionOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionPolicyTest {

    private val policy = ToolExecutionPolicy()

    @Test
    fun `background denies never-autonomous tools regardless of confirmation flag`() {
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
    fun `background denies confirmation-required tools instead of waiting on a dialog`() {
        val decision = policy.evaluate(
            ExecutionOrigin.BACKGROUND,
            "calendar_add_event",
            requiresConfirmation = true,
        )
        assertTrue(decision is ToolExecutionDecision.Deny)
    }

    @Test
    fun `background allows ordinary tools`() {
        assertEquals(
            ToolExecutionDecision.Allow,
            policy.evaluate(ExecutionOrigin.BACKGROUND, "search_notes", requiresConfirmation = false),
        )
    }

    @Test
    fun `interactive gates never-autonomous tools even without the descriptor flag`() {
        assertEquals(
            ToolExecutionDecision.Confirm,
            policy.evaluate(ExecutionOrigin.INTERACTIVE, "shell", requiresConfirmation = false),
        )
    }

    @Test
    fun `interactive gates confirmation-required tools`() {
        assertEquals(
            ToolExecutionDecision.Confirm,
            policy.evaluate(ExecutionOrigin.INTERACTIVE, "calendar_add_event", requiresConfirmation = true),
        )
    }

    @Test
    fun `interactive allows ordinary tools`() {
        assertEquals(
            ToolExecutionDecision.Allow,
            policy.evaluate(ExecutionOrigin.INTERACTIVE, "search_notes", requiresConfirmation = false),
        )
    }
}
