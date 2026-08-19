package com.hermes.agent.data.agent.agents

import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry

/**
 * Per-agent capability-based tool access policy.
 *
 * Each [AgentRole] grants a set of capabilities rather than hardcoding tool names.
 * Tools advertise their capabilities via [ToolDescriptor.capabilities].
 * A tool is accessible to an agent if any of its declared capabilities intersect
 * with the agent role's granted capabilities.
 */
internal object AgentToolAccess {

    private val COMMON_CAPABILITIES = setOf("common")

    val ROLE_CAPABILITIES: Map<AgentRole, Set<String>> = mapOf(
        AgentRole.CONVERSATIONAL to COMMON_CAPABILITIES + setOf(
            "datetime", "memory", "notes", "search_conversations",
            "skill_manager", "scheduler", "web", "calculator", "delegate",
            "media:image", "media:tts", "notifications",
            "system:shell", "system:termux",
        ),
        AgentRole.PRODUCTIVITY to COMMON_CAPABILITIES + setOf(
            "datetime", "calendar", "memory", "notes",
            "search_conversations", "skill_manager", "scheduler", "calculator",
            "web", "delegate", "notifications", "contacts", "device:navigation",
        ),
        AgentRole.RESEARCH to COMMON_CAPABILITIES + setOf(
            "web", "search_conversations", "memory", "notes",
            "skill_manager", "calculator", "delegate",
        ),
        AgentRole.DEVICE_CONTROL to COMMON_CAPABILITIES + setOf(
            "datetime", "memory", "media:tts", "contacts",
            "device:settings", "system:shell", "system:termux",
            "device:app_automation", "device:alarm", "device:navigation",
            "device:media", "device:control",
        ),
        AgentRole.CREATIVE to COMMON_CAPABILITIES + setOf(
            "memory", "notes", "search_conversations", "skill_manager",
            "media:image", "web", "media:tts",
        ),
    )

    /** Look up the tool descriptors this agent is allowed to invoke. */
    fun ToolRegistry.toolsFor(
        role: AgentRole,
    ): List<ToolDescriptor> {
        val granted = ROLE_CAPABILITIES[role] ?: emptySet()
        return descriptors().filter { descriptor ->
            descriptor.capabilities.any { it in granted }
        }
    }

    /** Convenience overload for the common "by name list" case. */
    fun ToolRegistry.toolsFor(names: List<String>): List<ToolDescriptor> =
        descriptors().filter { it.name in names.toSet() }
}
