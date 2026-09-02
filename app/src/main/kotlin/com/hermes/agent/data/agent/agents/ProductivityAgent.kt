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
 * Tool access is decided by [AgentToolAccess]; replies should be
 * action-oriented: confirm what was done (or what's about to be done)
 * rather than offering abstract advice.
 */
@Singleton
class ProductivityAgent @Inject constructor() : Agent {

    override val role: AgentRole = AgentRole.PRODUCTIVITY

    override val systemPrompt: String =
        "You are the Hermes Productivity Agent. You help the user manage tasks, " +
            "scheduling, reminders, and automation.\n\n" +
            "Call a tool whenever one fits — don't just describe what you could do. " +
            "Be action-oriented: confirm what you did, not what you could do.\n\n" +
            "For complex or multi-phase projects, use kanban(action='create_batch', tickets=[...]) to " +
            "break them into structured tickets. For personal tasks and reminders, use todo. " +
            "For recurring requests use scheduler(action='create'). For one-off events use " +
            "calendar(action='create'). If timing is ambiguous, ask one short clarifying question."

    override fun availableTools(registry: ToolRegistry): List<ToolDescriptor> =
        registry.toolsFor(role)
}
