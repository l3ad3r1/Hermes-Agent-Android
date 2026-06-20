package com.hermes.agent.data.agent.agents

import com.hermes.agent.data.agent.agents.AgentToolAccess.toolsFor
import com.hermes.agent.domain.agent.Agent
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Productivity agent — calendar, tasks, email drafts, reminders.
 *
 * Has access to calendar, notes, and datetime tools. Replies should be
 * action-oriented: confirm what was done (or what's about to be done)
 * rather than offering abstract advice.
 */
@Singleton
class ProductivityAgent @Inject constructor() : Agent {

    override val role: AgentRole = AgentRole.PRODUCTIVITY

    override val systemPrompt: String =
        "You are the Hermes Productivity Agent. You help the user manage their " +
            "calendar, tasks, notes, and email drafts. Be action-oriented: confirm what " +
            "you did (event created, note saved) rather than describing what you could do. " +
            "When the user asks to schedule something, extract title, time, and duration " +
            "and call the calendar_add_event tool. For ambiguous times, ask a single " +
            "short clarifying question rather than guessing."

    override fun availableTools(registry: ToolRegistry): List<ToolDescriptor> =
        registry.toolsFor(role)
}
