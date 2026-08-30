package com.hermes.agent.data.agent.agents

import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry

/**
 * Per-agent capability-based tool access control.
 *
 * Each agent role declares the capability classes and categories it is granted.
 * When a tool is registered (at compile-time or dynamically at runtime via plugins),
 * it is offered to an agent if its declared category or capabilities match the agent's
 * grant list and are not in its excluded capabilities.
 */
internal object AgentToolAccess {

    private data class RoleGrant(
        val categories: Set<String> = emptySet(),
        val capabilities: Set<String> = emptySet(),
        val excludedCapabilities: Set<String> = emptySet(),
    ) {
        fun allows(descriptor: ToolDescriptor): Boolean {
            val allToolCaps = descriptor.capabilities + descriptor.category + descriptor.name
            if (excludedCapabilities.any { it in allToolCaps }) return false
            return descriptor.category in categories || capabilities.any { it in allToolCaps }
        }
    }

    private val GRANTS: Map<AgentRole, RoleGrant> = mapOf(
        AgentRole.CONVERSATIONAL to RoleGrant(
            // "device" is deliberately absent. It is a category, so granting it
            // hands this role every current and future device-category tool —
            // which silently included app_tap/app_type/app_swipe/app_launch/
            // app_analyze_screen, tools that can drive any app on the phone.
            // Every device tool this role legitimately needs is granted below by
            // capability instead, so a new device tool has to be granted on
            // purpose rather than arriving pre-approved.
            categories = setOf("information", "memory", "productivity", "communication", "creative", "system", "automation"),
            capabilities = setOf(
                "common", "time", "web", "conversation_search", "calculator", "notification",
                "notes", "device_alarm", "notes_and_reminders", "navigation", "phone", "contacts",
                "media", "device_control", "skills", "user_memory", "scheduler", "shell", "termux",
                "todo", "voice", "clarify", "delegate", "media_generation", "documents", "kanban",
                "bookmarks", "mood", "home_assistant",
            ),
            // Belt and braces: excludedCapabilities is checked before anything
            // else, so app automation stays out of a conversational turn even if
            // a future tool re-introduces it through a category.
            excludedCapabilities = setOf("calendar", "device_settings", "app_automation"),
        ),
        AgentRole.PRODUCTIVITY to RoleGrant(
            capabilities = setOf(
                "common", "time", "web", "conversation_search", "calculator", "calendar", "notes",
                "skills", "user_memory", "scheduler", "todo", "clarify", "delegate", "notification",
                "phone", "contacts", "navigation", "documents", "notes_and_reminders", "kanban",
                "bookmarks", "mood",
            ),
        ),
        AgentRole.RESEARCH to RoleGrant(
            capabilities = setOf(
                "common", "web", "conversation_search", "user_memory", "notes", "skills",
                "calculator", "delegate", "bookmarks", "todo",
            ),
        ),
        AgentRole.DEVICE_CONTROL to RoleGrant(
            categories = setOf("automation", "system"),
            capabilities = setOf(
                "common", "device_settings", "time", "user_memory", "shell", "termux", "voice",
                "app_automation", "device_alarm", "navigation", "media", "device_control",
                "phone", "contacts", "home_assistant",
            ),
        ),
        AgentRole.CREATIVE to RoleGrant(
            capabilities = setOf(
                "common", "user_memory", "notes", "conversation_search", "skills",
                "media_generation", "web", "voice", "creative", "bookmarks",
            ),
        ),
    )

    /** Look up the tool descriptors this agent is allowed to invoke. */
    fun ToolRegistry.toolsFor(
        role: AgentRole,
    ): List<ToolDescriptor> {
        val grant = GRANTS[role] ?: return emptyList()
        return descriptors().filter { grant.allows(it) }
    }

    /** Convenience overload for the common "by name list" case. */
    fun ToolRegistry.toolsFor(names: List<String>): List<ToolDescriptor> =
        descriptors().filter { it.name in names.toSet() }
}
