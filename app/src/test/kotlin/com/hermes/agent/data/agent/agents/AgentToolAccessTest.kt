package com.hermes.agent.data.agent.agents

import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the per-agent tool allowlist: a tool the LLM is never told about can
 * never be called, so every registered tool must be granted to at least one
 * agent. Regression test for v0.7.4–0.7.8 tools (todo/clarify/delegate/speak/
 * generate_image) that were registered but missing from every allowlist.
 */
class AgentToolAccessTest {

    /** Minimal registry that just advertises a fixed set of descriptor names. */
    private class FakeRegistry(names: List<String>) : ToolRegistry {
        private val descs = names.map { ToolDescriptor(it, "", emptyList()) }
        override fun all(): List<Tool> = emptyList()
        override fun descriptors(): List<ToolDescriptor> = descs
        override fun byName(name: String): Tool? = null
        override fun register(tool: Tool) {}
        override fun unregister(name: String) {}
    }

    private val allToolNames = listOf(
        "get_current_datetime", "calculator", "web_search", "web_fetch", "notify",
        "device_settings", "notes", "search_conversations", "calendar_add_event",
        "skill_manager", "memory", "scheduler", "shell", "termux", "todo", "speak",
        "clarify", "delegate", "generate_image",
        // Cross-feature tools (Phase 6): reach :feature:jotter and :feature:butler.
        "create_note", "set_alarm",
    )

    private val agents = listOf(
        ConversationalAgent(), ProductivityAgent(), ResearchAgent(),
        DeviceControlAgent(), CreativeAgent(),
    )

    private fun grantedAnywhere(tool: String): Boolean {
        val registry = FakeRegistry(allToolNames)
        return agents.any { agent -> agent.availableTools(registry).any { it.name == tool } }
    }

    @Test
    fun `new v0_7_x tools are granted to at least one agent`() {
        for (tool in listOf("todo", "clarify", "delegate", "speak", "generate_image", "web_fetch")) {
            assertTrue("'$tool' is not granted to any agent — the LLM can never call it", grantedAnywhere(tool))
        }
    }

    @Test
    fun `conversational agent (default) exposes the core new tools`() {
        val names = ConversationalAgent().availableTools(FakeRegistry(allToolNames)).map { it.name }
        for (tool in listOf("todo", "clarify", "delegate", "speak", "generate_image", "web_fetch")) {
            assertTrue("conversational agent missing '$tool'", names.contains(tool))
        }
    }

    @Test
    fun `creative agent exposes generate_image`() {
        val names = CreativeAgent().availableTools(FakeRegistry(allToolNames)).map { it.name }
        assertTrue(names.contains("generate_image"))
    }

    @Test
    fun `cross-feature tools are granted to at least one agent`() {
        for (tool in listOf("create_note", "set_alarm")) {
            assertTrue("'$tool' is not granted to any agent — the LLM can never call it", grantedAnywhere(tool))
        }
    }

    /**
     * Step 3 of the tool-wiring rule: registering and granting a tool is not enough — the
     * persona prompt must name it, or the model has no idea when to reach for it.
     */
    @Test
    fun `agents that are granted the cross-feature tools also advertise them in the prompt`() {
        for (agent in listOf(ConversationalAgent(), ProductivityAgent())) {
            val granted = agent.availableTools(FakeRegistry(allToolNames)).map { it.name }
            for (tool in listOf("create_note", "set_alarm")) {
                assertTrue("${agent.role} is not granted '$tool'", granted.contains(tool))
                assertTrue(
                    "${agent.role} grants '$tool' but never mentions it in its system prompt",
                    agent.systemPrompt.contains(tool),
                )
            }
        }
    }
}
