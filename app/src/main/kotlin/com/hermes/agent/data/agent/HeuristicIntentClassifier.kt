package com.hermes.agent.data.agent

import com.hermes.agent.domain.agent.AgentRouter
import com.hermes.agent.domain.agent.RoutingResult
import com.hermes.agent.domain.model.AgentRole
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Heuristic intent classifier + agent router.
 *
 * Phase 2 routes by keyword matching against the user's prompt. This is
 * deliberately simple — the goal is to have a working multi-agent system
 * end-to-end; the routing quality will be replaced by a lightweight
 * on-device classifier in Phase 3.
 *
 * Routing rules (first match wins):
 *   - device-control keywords (settings, brightness, volume, wifi,
 *     bluetooth, open app, launch) → DeviceControlAgent
 *   - research keywords (search, look up, find, what's new, latest) →
 *     ResearchAgent
 *   - productivity keywords (schedule, meeting, calendar, task, todo,
 *     email, draft, reminder) → ProductivityAgent
 *   - creative keywords (write, story, poem, brainstorm, draft a,
 *     rewrite) → CreativeAgent
 *   - everything else → ConversationalAgent
 *
 * Multi-agent routing (Phase 2 limited): if the prompt contains a
 * research-then-write pattern ("search for X then draft a Y"), the
 * orchestrator runs Research → Creative in sequence.
 */
@Singleton
class HeuristicIntentClassifier @Inject constructor() : AgentRouter {

    override suspend fun route(userMessage: String): RoutingResult {
        val p = userMessage.lowercase()

        // Multi-agent pattern: research-then-create.
        if (MULTI_AGENT_PATTERN.containsMatchIn(p)) {
            return RoutingResult.MultiAgent(
                agents = listOf(AgentRole.RESEARCH, AgentRole.CREATIVE),
                planSummary = "Research the topic, then draft a response based on findings.",
            )
        }

        // Single-agent routing by keyword.
        for ((role, keywords) in ROUTING_RULES) {
            if (keywords.any { p.contains(it) }) {
                return RoutingResult.Solo(role, confidence = 0.75f)
            }
        }

        return RoutingResult.Solo(AgentRole.CONVERSATIONAL, confidence = 0.5f)
    }

    companion object {
        private val MULTI_AGENT_PATTERN = Regex(
            """(search.+then.+(write|draft|summar|create))|""" +
                """(find.+(and|then).+(write|draft|summar|create))|""" +
                """(research.+(and|then).+(write|draft|compose))"""
        )

        /**
         * Order matters: more specific agents first.
         * DeviceControl before Productivity (so "schedule a settings change"
         * doesn't accidentally route to Productivity).
         */
        private val ROUTING_RULES: List<Pair<AgentRole, List<String>>> = listOf(
            AgentRole.DEVICE_CONTROL to listOf(
                "brightness", "volume", "turn on", "turn off", "open the app",
                "launch", "open settings", "enable wifi", "disable bluetooth",
                "do not disturb", "airplane mode",
            ),
            AgentRole.RESEARCH to listOf(
                "search", "look up", "look this up", "find ", "what's the latest",
                "what is the latest", "what's new", "current price", "news about",
                "google ", "research ",
            ),
            AgentRole.PRODUCTIVITY to listOf(
                "schedule", "meeting", "calendar", "add an event", "create a task",
                "add to my todo", "remind me", "set a reminder", "draft an email",
                "draft a message", "to-do",
            ),
            AgentRole.CREATIVE to listOf(
                "write a", "compose a", "draft a poem", "draft a story",
                "brainstorm", "rewrite", "polish this", "improve this text",
                "expand on", "elaborate creatively",
            ),
        )
    }
}
