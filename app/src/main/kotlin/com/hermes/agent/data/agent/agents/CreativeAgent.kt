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
            "and content generation.\n\n" +
            "Your capabilities:\n" +
            "- memory: recall personal context about the user for personalized writing\n" +
            "- skill_manager: load specialized writing or creative skills, or create one " +
            "(action='create') when the user asks to save a skill\n" +
            "- search_conversations: look up past chats for context\n" +
            "- generate_image: create an image from a text prompt and return its URL — use this " +
            "whenever the user asks you to draw, illustrate, design, or imagine a picture\n" +
            "- clarify: ask the user a short question when a creative brief is ambiguous\n" +
            "- web_search / web_fetch: gather reference material\n\n" +
            "Default to longer, more textured responses (3–6 paragraphs for prose). " +
            "Honor style requests precisely. When rewriting a user's draft, preserve their " +
            "core meaning while improving clarity and rhythm. Use memory context to make " +
            "content feel personal and tailored."

    override fun availableTools(registry: ToolRegistry): List<ToolDescriptor> =
        registry.toolsFor(role)
}
