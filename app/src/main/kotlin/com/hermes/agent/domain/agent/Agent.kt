package com.hermes.agent.domain.agent

import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmToolResponse
import com.hermes.agent.data.llm.ToolCall
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.tool.ToolDescriptor

/**
 * Contract every specialized Hermes agent must satisfy.
 *
 * Phase 2 ships five implementations under `data/agent/agents/`:
 *   - ConversationalAgent
 *   - ProductivityAgent
 *   - ResearchAgent
 *   - DeviceControlAgent
 *   - CreativeAgent
 *
 * An agent is a thin configuration layer over the LlmProvider: it picks
 * the system prompt, decides which tools are available, and may post-
 * process the LLM's reply. State (memory, conversation context) is
 * owned by the orchestrator, not the agent.
 */
interface Agent {

    /** Which specialized persona this agent embodies. */
    val role: AgentRole

    /** System prompt injected at the top of every LLM call this agent makes. */
    val systemPrompt: String

    /**
     * Tools this agent is allowed to invoke. Subset of the global
     * [com.hermes.agent.domain.tool.ToolRegistry]. The orchestrator
     * passes these to the LLM as the `tools` array.
     */
    fun availableTools(registry: com.hermes.agent.domain.tool.ToolRegistry): List<ToolDescriptor>

    /**
     * Quick relevance check: can this agent usefully handle the given
     * user message? Used by [AgentRouter] to pick a primary agent.
     *
     * Default: false. Implementations override with their own heuristic.
     */
    suspend fun canHandle(userMessage: String): Boolean = false

    /**
     * Post-process the LLM's tool-call response. Default: return as-is.
     *
     * Override to inject agent-specific formatting (e.g. the Research
     * agent appends source citations, the Creative agent strips
     * meta-commentary).
     */
    suspend fun postProcess(response: LlmToolResponse): LlmToolResponse = response
}
