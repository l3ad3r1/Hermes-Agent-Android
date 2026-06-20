package com.hermes.agent.data.agent

import com.hermes.agent.domain.agent.Agent
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.data.agent.agents.CreativeAgent
import com.hermes.agent.data.agent.agents.ConversationalAgent
import com.hermes.agent.data.agent.agents.DeviceControlAgent
import com.hermes.agent.data.agent.agents.ProductivityAgent
import com.hermes.agent.data.agent.agents.ResearchAgent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lookup table from [AgentRole] to the singleton [Agent] instance.
 *
 * Populated at construction with all five Phase 2 agents. Phase 3 will
 * load additional agents dynamically from plugin APKs.
 */
@Singleton
class AgentRegistry @Inject constructor(
    conversational: ConversationalAgent,
    productivity: ProductivityAgent,
    research: ResearchAgent,
    deviceControl: DeviceControlAgent,
    creative: CreativeAgent,
) {
    private val byRole: Map<AgentRole, Agent> = mapOf(
        AgentRole.CONVERSATIONAL to conversational,
        AgentRole.PRODUCTIVITY to productivity,
        AgentRole.RESEARCH to research,
        AgentRole.DEVICE_CONTROL to deviceControl,
        AgentRole.CREATIVE to creative,
    )

    fun get(role: AgentRole): Agent =
        byRole[role] ?: byRole.getValue(AgentRole.CONVERSATIONAL)

    fun all(): List<Agent> = byRole.values.toList()
}
