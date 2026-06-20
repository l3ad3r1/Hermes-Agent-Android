package com.hermes.agent.data.agent.agents

import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry

/**
 * Per-agent tool access lists. Centralized here so the access policy
 * is auditable in one place — Section 6.1 of the plan specifies that
 * "each agent maintains its own context window, tool access permissions,
 * and response formatting preferences."
 *
 * Phase 2 access policy:
 *
 * | Agent            | Tools                                                     |
 * |------------------|-----------------------------------------------------------|
 * | Conversational   | datetime, notes, search_conversations                     |
 * | Productivity     | datetime, calendar_add_event, notes, search_conversations |
 * | Research         | web_search, search_conversations, notes                   |
 * | Device Control   | device_settings, datetime                                  |
 * | Creative         | notes, search_conversations                               |
 *
 * Phase 3 will move this to a per-agent config file (YAML or JSON) so
 * plugin authors can declare their own agent personas with custom tool
 * sets.
 */
internal object AgentToolAccess {

    private val ACCESS: Map<com.hermes.agent.domain.model.AgentRole, Set<String>> = mapOf(
        com.hermes.agent.domain.model.AgentRole.CONVERSATIONAL to setOf(
            "get_current_datetime", "notes", "search_conversations",
        ),
        com.hermes.agent.domain.model.AgentRole.PRODUCTIVITY to setOf(
            "get_current_datetime", "calendar_add_event", "notes", "search_conversations",
        ),
        com.hermes.agent.domain.model.AgentRole.RESEARCH to setOf(
            "web_search", "search_conversations", "notes",
        ),
        com.hermes.agent.domain.model.AgentRole.DEVICE_CONTROL to setOf(
            "device_settings", "get_current_datetime",
        ),
        com.hermes.agent.domain.model.AgentRole.CREATIVE to setOf(
            "notes", "search_conversations",
        ),
    )

    /** Look up the tool descriptors this agent is allowed to invoke. */
    fun ToolRegistry.toolsFor(
        role: com.hermes.agent.domain.model.AgentRole,
    ): List<ToolDescriptor> {
        val allowed = ACCESS[role] ?: emptySet()
        return descriptors().filter { it.name in allowed }
    }

    /** Convenience overload for the common "by name list" case. */
    fun ToolRegistry.toolsFor(names: List<String>): List<ToolDescriptor> =
        descriptors().filter { it.name in names.toSet() }
}
