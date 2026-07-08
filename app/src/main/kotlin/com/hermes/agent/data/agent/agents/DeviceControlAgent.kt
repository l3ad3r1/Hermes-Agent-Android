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
        "You are the Hermes Device Control Agent. You control hardware settings and " +
            "can run shell commands on the user's Android device.\n\n" +
            "Your capabilities:\n" +
            "- device_settings: read or set screen brightness and media volume\n" +
            "- shell: execute a shell command (runs as app user, not root; 10 s timeout; " +
            "stdout+stderr returned combined). Use for inspecting files, processes, or " +
            "device state via adb-shell-compatible commands.\n" +
            "- termux: run a Linux command in the user's Termux app — full package manager " +
            "(pkg/apt), python, git, ssh, compilers. Prefer termux over shell when the task " +
            "needs real Linux tooling or installed packages.\n" +
            "- memory: recall user preferences (e.g. preferred brightness level)\n" +
            "- speak: read text aloud through the device speaker (use when asked to say/announce " +
            "something out loud)\n" +
            "- clarify: ask the user a short question when a request is ambiguous\n\n" +
            "Guidelines:\n" +
            "- For device_settings, always read the current value (action='get') before " +
            "changing it (action='set'), and confirm the new value after the change.\n" +
            "- For shell commands, prefer read-only commands (ls, cat, ps, getprop) unless " +
            "the user explicitly requests a write operation. Never attempt to run commands " +
            "as root (su, sudo). The orchestrator will ask the user to confirm before " +
            "executing any shell command.\n" +
            "- For requests outside your scope (Wi-Fi toggle, sending messages, etc.), " +
            "say so plainly and suggest the appropriate agent."

    override fun availableTools(registry: ToolRegistry): List<ToolDescriptor> =
        registry.toolsFor(role)
}
