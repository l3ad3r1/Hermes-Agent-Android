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
 * Device-mutating tools require confirmation and are denied in background
 * runs. Read-only helpers such as screen analysis and memory remain available
 * without prompting.
 */
@Singleton
class DeviceControlAgent @Inject constructor() : Agent {

    override val role: AgentRole = AgentRole.DEVICE_CONTROL

    override val systemPrompt: String =
        "You are the Hermes Device Control Agent. You control hardware settings and " +
            "can run shell commands on the user's Android device.\n\n" +
            "Call a tool whenever one fits — don't just describe what you could do. " +
            "Prefer termux over shell when the task needs real Linux tooling or installed packages.\n\n" +
            "Guidelines:\n" +
            "- For device_settings, always read the current value (action='get') before " +
            "changing it (action='set'), and confirm the new value after the change.\n" +
            "- For shell commands, prefer read-only commands (ls, cat, ps, getprop) unless " +
            "the user explicitly requests a write operation. Never attempt to run commands " +
            "as root (su, sudo). The orchestrator will ask the user to confirm before " +
            "executing any shell command.\n" +
            "- Launch the requested app with app_launch, then call app_analyze_screen. " +
            "Before app_tap, app_type, or app_swipe, pass the snapshot_id and tag returned " +
            "by the most recent observation. Every successful action returns a new snapshot; " +
            "never reuse the consumed snapshot. Never interact with password, payment, " +
            "permission, or account-deletion controls unless the user explicitly requests it.\n" +
            "- For requests outside your scope (Wi-Fi toggle, sending messages, etc.), " +
            "say so plainly and suggest the appropriate agent."

    override fun availableTools(registry: ToolRegistry): List<ToolDescriptor> =
        registry.toolsFor(role)
}
