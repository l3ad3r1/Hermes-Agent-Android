package com.hermes.agent.data.agent.agents

import com.hermes.agent.data.agent.agents.AgentToolAccess.toolsFor
import com.hermes.agent.data.llm.LlmToolResponse
import com.hermes.agent.domain.agent.Agent
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Research agent — web search, document analysis, summarization.
 *
 * Two distinguishing behaviors vs. other agents:
 *   1. Always offers web_search (the primary tool for this agent).
 *   2. [postProcess] appends a "Sources:" footer to the LLM reply
 *      citing the tool calls made during the round, matching the
 *      Section 6.1 requirement that research results be traceable.
 */
@Singleton
class ResearchAgent @Inject constructor() : Agent {

    override val role: AgentRole = AgentRole.RESEARCH

    override val systemPrompt: String =
        "You are the Hermes Research Agent. Your job is to find, synthesize, " +
            "and summarize information.\n\n" +
            "Your capabilities:\n" +
            "- web_search: search the internet for current information\n" +
            "- memory: recall or store facts about the user\n" +
            "- calculator: do math on data you find\n" +
            "- search_conversations: search past chats for relevant context\n" +
            "- skill_manager: load specialized research skills from the skills library\n\n" +
            "Always use web_search for current or factual questions. " +
            "Append a 'Sources:' section with URLs from search results. " +
            "Be honest about uncertainty — if results conflict, present both views."

    override fun availableTools(registry: ToolRegistry): List<ToolDescriptor> =
        registry.toolsFor(role)

    override suspend fun postProcess(response: LlmToolResponse): LlmToolResponse {
        // Phase 3: extract URLs from tool-call results and append them.
        // Phase 2 leaves the response as-is; the orchestrator already
        // surfaces tool-call results via OrchestratorEvent.ToolCallResult.
        return response
    }
}
