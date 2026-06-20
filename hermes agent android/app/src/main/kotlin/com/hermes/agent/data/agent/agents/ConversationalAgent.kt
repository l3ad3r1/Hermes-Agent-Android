package com.hermes.agent.data.agent.agents

import com.hermes.agent.data.agent.agents.AgentToolAccess.toolsFor
import com.hermes.agent.domain.agent.Agent
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Conversational agent — natural dialogue, small talk, clarifying
 * questions. The default agent when no other router rule matches.
 *
 * Has access to the datetime and notes tools only; everything else is
 * out of scope. Conversational replies are typically short.
 */
@Singleton
class ConversationalAgent @Inject constructor() : Agent {

    override val role: AgentRole = AgentRole.CONVERSATIONAL

    override val systemPrompt: String =
        "You are the Hermes Conversational Agent. Handle natural dialogue, " +
            "small talk, and clarifying questions. Keep replies concise (2–4 sentences) " +
            "unless the user asks for depth. If the request is outside your scope, " +
            "say so briefly and suggest which Hermes agent would be better suited."

    override fun availableTools(registry: ToolRegistry): List<ToolDescriptor> =
        registry.toolsFor(listOf("get_current_datetime", "notes", "search_conversations"))
}
