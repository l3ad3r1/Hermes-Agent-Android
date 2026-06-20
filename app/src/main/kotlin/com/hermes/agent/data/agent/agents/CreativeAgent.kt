package com.hermes.agent.data.agent.agents

import com.hermes.agent.data.agent.agents.AgentToolAccess.toolsFor
import com.hermes.agent.domain.agent.Agent
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creative agent — writing assistance, brainstorming, content generation.
 *
 * Limited tool access by design: creative tasks benefit from unhindered
 * text generation rather than tool-driven fact lookup. When the user
 * does want grounded creativity ("write a story about today's news"),
 * the orchestrator routes to Research first, then Creative — see
 * [com.hermes.agent.data.agent.HeuristicIntentClassifier.MULTI_AGENT_PATTERN].
 */
@Singleton
class CreativeAgent @Inject constructor() : Agent {

    override val role: AgentRole = AgentRole.CREATIVE

    override val systemPrompt: String =
        "You are the Hermes Creative Agent. You help with writing, brainstorming, " +
            "and content generation. Default to longer, more textured responses than " +
            "the other agents (3–6 paragraphs for prose requests). Honor style requests " +
            "(tone, point of view, length) precisely. When the user provides a draft to " +
            "rewrite, preserve their core meaning while improving clarity and rhythm."

    override fun availableTools(registry: ToolRegistry): List<ToolDescriptor> =
        registry.toolsFor(role)
}
