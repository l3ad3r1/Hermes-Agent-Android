package com.hermes.agent.domain.agent

import com.hermes.agent.domain.model.AgentRole

/**
 * Result of an [AgentRouter.route] call.
 */
sealed class RoutingResult {
    /**
     * A single agent will handle the request end-to-end. This is the
     * common case in Phase 2 — only the CONVERSATIONAL, RESEARCH, and
     * PRODUCTIVITY agents are wired for solo handling.
     */
    data class Solo(val agent: AgentRole, val confidence: Float) : RoutingResult()

    /**
     * Multiple agents will collaborate. The orchestrator runs them in
     * plan order, passing intermediate results forward.
     *
     * Phase 2 only emits this for prompts that explicitly need
     * cross-domain work (e.g. "draft an email summarizing my last
     * meeting and schedule a follow-up"). Phase 3 will expand the
     * planner to handle richer multi-step requests.
     */
    data class MultiAgent(val agents: List<AgentRole>, val planSummary: String) : RoutingResult()

    /**
     * No agent could be selected. The orchestrator falls back to the
     * Conversational agent with a generic system prompt.
     */
    data class Fallback(val reason: String) : RoutingResult()
}

/**
 * Picks which agent(s) should handle a user message.
 *
 * Phase 2 uses a heuristic classifier (keyword + length) backed by
 * [HeuristicIntentClassifier]. Phase 3 will swap in a lightweight
 * on-device intent model.
 */
interface AgentRouter {
    suspend fun route(userMessage: String): RoutingResult
}
