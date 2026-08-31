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
            "Your capabilities:\n" +
            "- device_settings: read or set screen brightness and media volume\n" +
            "- shell: execute a shell command (target='local' runs as app user, target='privileged' runs with elevated ADB privileges via Shizuku, target='remote' runs over SSH). Use for inspecting files, processes, packages, or device state.\n" +
            "- termux: run a Linux command in the user's Termux app — full package manager " +
            "(pkg/apt), python, git, ssh, compilers. Prefer termux over shell when the task " +
            "needs real Linux tooling or installed packages.\n" +
            "- app_launch: launch an installed Android app by package name\n" +
            "- app_analyze_screen: inspect the visible app and receive a snapshot with tagged controls\n" +
            "- app_tap: tap a tagged control from that exact snapshot\n" +
            "- app_swipe: swipe the screen represented by that snapshot\n" +
            "- app_type: enter text into a tagged editable field from that snapshot\n" +
            "- memory: recall user preferences (e.g. preferred brightness level)\n" +
            "- home_assistant: control smart home devices, inspect entity states, and list services via Home Assistant (action='list_entities', action='get_state', action='list_services', action='call_service')\n" +
            "- speak: read text aloud through the device speaker (use when asked to say/announce " +
            "- communication: open the dialer, compose an SMS or email, or open the add-contact screen (action='dial'|'compose_sms'|'compose_email'|'add_contact', recipient, message, subject). It only opens the composer - it never places a call or sends a message by itself, so the user always confirms in the app.\n" +
            "- contact_lookup: find a contact and return their phone numbers (query = the name to search for)\n" +
            "- navigation: start turn-by-turn navigation, search nearby places, or show a location on a map (action='navigate'|'search_nearby'|'show_map', query, optional mode='driving'|'walking'|'bicycling'|'transit')\n" +
            "- device_control: flashlight, volume, ringer mode, Do Not Disturb and screen brightness (action='flashlight'|'set_volume'|'set_ringer_mode'|'set_dnd'|'set_brightness', enabled, level 0-255, stream, mode)\n" +
            "- media_control: play or pause media, skip tracks, or ask a music app to play something (action='play_pause'|'next'|'previous'|'play_search', query)\n" +
            "something out loud)\n" +
            "- clarify: ask the user a short question when a request is ambiguous\n\n" +
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
