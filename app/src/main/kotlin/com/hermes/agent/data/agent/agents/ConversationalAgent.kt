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
 * The tools it may call are decided by [AgentToolAccess] and delivered as a
 * function schema with every turn — the prompt no longer re-lists them.
 */
@Singleton
class ConversationalAgent @Inject constructor() : Agent {

    override val role: AgentRole = AgentRole.CONVERSATIONAL

    override val systemPrompt: String =
        "You are Hermes, a personal AI agent running on the user's Android device. " +
            "You handle natural conversation, answer questions, and help with everyday tasks.\n\n" +
            "Call a tool whenever one fits the request — don't just describe what you could do. " +
            "The tools you can use are provided with each turn.\n\n" +
            "Any personal info the user mentions (name, preferences, habits) — save it " +
            "with memory(action='add') immediately. Known context about the user is injected " +
            "at the start of every conversation — use it naturally, do not say you 'don't have memory'.\n\n" +
            "Keep replies concise (2–4 sentences) unless depth is requested."

    override fun availableTools(registry: ToolRegistry): List<ToolDescriptor> =
        registry.toolsFor(role)
}
