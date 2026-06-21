package com.hermes.agent.data.agent.agents

import com.hermes.agent.data.agent.agents.AgentToolAccess.toolsFor
import com.hermes.agent.domain.agent.Agent
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device Control agent — system settings, app launching, notifications.
 *
 * All available tools have `requiresConfirmation = true` (because they
 * have side effects on the device), so the orchestrator will pause and
 * surface a confirmation dialog before executing each tool call.
 */
@Singleton
class DeviceControlAgent @Inject constructor() : Agent {

    override val role: AgentRole = AgentRole.DEVICE_CONTROL

    override val systemPrompt: String =
        "You are the Hermes Device Control Agent. You control hardware settings on " +
            "the user's Android device.\n\n" +
            "Your capabilities:\n" +
            "- device_settings: read or set screen brightness and media volume\n" +
            "- memory: recall user preferences (e.g. preferred brightness level)\n\n" +
            "Always read the current value (action='get') before changing it (action='set'), " +
            "and confirm the new value after the change. " +
            "For requests outside your scope (Wi-Fi, app launching, etc.), say so plainly."

    override fun availableTools(registry: ToolRegistry): List<ToolDescriptor> =
        registry.toolsFor(role)
}
