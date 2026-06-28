package com.hermes.agent.data.agent.agents

import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry

/**
 * Per-agent tool access lists. Centralized here so the access policy
 * is auditable in one place — Section 6.1 of the plan specifies that
 * "each agent maintains its own context window, tool access permissions,
 * and response formatting preferences."
 *
 * Access policy: every agent gets [COMMON] (todo, clarify) plus a
 * persona-specific set (see [ACCESS] below). When a new tool is added it MUST
 * be granted to at least one agent here, or the LLM is never told it exists and
 * can never call it.
 *
 * Phase 3 will move this to a per-agent config file (YAML or JSON) so
 * plugin authors can declare their own agent personas with custom tool
 * sets.
 */
internal object AgentToolAccess {

    // `todo` and `clarify` are useful to every agent (plan/track work; ask the
    // user when ambiguous), so they're granted across the board. The richer
    // tools added in v0.7.4+ (delegate/speak/generate_image/web_fetch) are
    // granted where they fit the persona.
    private val COMMON = setOf("todo", "clarify")

    private val ACCESS: Map<com.hermes.agent.domain.model.AgentRole, Set<String>> = mapOf(
        com.hermes.agent.domain.model.AgentRole.CONVERSATIONAL to COMMON + setOf(
            "get_current_datetime", "memory", "notes", "search_conversations",
            "skill_manager", "scheduler", "web_search", "web_fetch", "calculator",
            "shell", "delegate", "speak", "generate_image",
        ),
        com.hermes.agent.domain.model.AgentRole.PRODUCTIVITY to COMMON + setOf(
            "get_current_datetime", "calendar_add_event", "memory", "notes",
            "search_conversations", "skill_manager", "scheduler", "calculator",
            "web_search", "web_fetch", "delegate",
        ),
        com.hermes.agent.domain.model.AgentRole.RESEARCH to COMMON + setOf(
            "web_search", "web_fetch", "search_conversations", "memory", "notes",
            "skill_manager", "calculator", "delegate",
        ),
        com.hermes.agent.domain.model.AgentRole.DEVICE_CONTROL to COMMON + setOf(
            "device_settings", "get_current_datetime", "memory", "shell", "speak",
        ),
        com.hermes.agent.domain.model.AgentRole.CREATIVE to COMMON + setOf(
            "memory", "notes", "search_conversations", "skill_manager",
            "generate_image", "web_search", "web_fetch", "speak",
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
