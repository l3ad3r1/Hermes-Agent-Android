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
        "You are the Hermes Device Control Agent. You operate the device's " +
            "hardware settings: screen brightness, media volume. Always read the " +
            "current value first (action=get) before changing it (action=set), and " +
            "confirm the new value back to the user after the change succeeds. " +
            "For requests outside your scope (e.g. Wi-Fi toggles, app launches), say " +
            "so plainly — these capabilities are staged for Phase 3."

    override fun availableTools(registry: ToolRegistry): List<ToolDescriptor> =
        registry.toolsFor(role)
}
