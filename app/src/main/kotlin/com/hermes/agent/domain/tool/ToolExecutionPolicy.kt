package com.hermes.agent.domain.tool

import com.hermes.agent.domain.agent.ExecutionOrigin
import javax.inject.Inject
import javax.inject.Singleton

/** Verdict for one tool call in one execution context. */
sealed interface ToolExecutionDecision {
    data object Allow : ToolExecutionDecision
    data object Confirm : ToolExecutionDecision
    data class Deny(val reason: String) : ToolExecutionDecision
}

/**
 * Context-aware execution policy (roadmap v0.13): interactive turns gate
 * dangerous tools behind the confirmation dialog; background turns deny
 * them outright with an actionable message instead of burning the
 * confirmation timeout with nobody to ask.
 */
@Singleton
class ToolExecutionPolicy @Inject constructor() {

    fun evaluate(
        origin: ExecutionOrigin,
        toolName: String,
        requiresConfirmation: Boolean,
    ): ToolExecutionDecision = when {
        origin == ExecutionOrigin.BACKGROUND && toolName in NEVER_AUTONOMOUS ->
            ToolExecutionDecision.Deny(
                "The '$toolName' tool is never allowed from background runs. " +
                    "Ask again from the chat screen where you can approve it.",
            )
        origin == ExecutionOrigin.BACKGROUND && requiresConfirmation ->
            ToolExecutionDecision.Deny(
                "The '$toolName' tool needs interactive approval and this run " +
                    "has no one to ask. Ask again from the chat screen.",
            )
        // Never-autonomous tools always face a human, even if a descriptor
        // forgets its requiresConfirmation flag.
        toolName in NEVER_AUTONOMOUS -> ToolExecutionDecision.Confirm
        requiresConfirmation -> ToolExecutionDecision.Confirm
        else -> ToolExecutionDecision.Allow
    }

    companion object {
        /** Tools that may run only with a human watching (roadmap v0.13). */
        val NEVER_AUTONOMOUS = setOf("shell", "termux", "device_settings")
    }
}
