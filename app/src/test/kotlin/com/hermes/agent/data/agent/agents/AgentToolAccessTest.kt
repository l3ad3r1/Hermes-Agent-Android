package com.hermes.agent.data.agent.agents

import com.hermes.agent.data.agent.agents.AgentToolAccess.toolsFor
import com.hermes.agent.data.plugin.InProcessPluginSandbox
import com.hermes.agent.data.tool.ToolRegistryImpl
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.plugin.Plugin
import com.hermes.agent.domain.plugin.PluginCapability
import com.hermes.agent.domain.plugin.PluginContext
import com.hermes.agent.domain.plugin.PluginLifecycleResult
import com.hermes.agent.domain.plugin.PluginManifest
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the per-agent capability-based tool access policy:
 * - Every registered tool is granted to at least one role.
 * - Each role receives the expected capability grants without silent widening.
 * - Runtime plugins loaded into [InProcessPluginSandbox] reach agent personas via declared capabilities.
 */
class AgentToolAccessTest {

    private class StubTool(
        override val descriptor: ToolDescriptor,
    ) : Tool {
        override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult =
            ToolResult.ok("stub")
    }

    private fun desc(name: String, category: String = "general", capabilities: Set<String> = setOf(category)) =
        ToolDescriptor(
            name = name,
            description = "stub for $name",
            parameters = emptyList(),
            category = category,
            capabilities = capabilities,
        )

    private val sampleTools = listOf(
        StubTool(desc("todo", "productivity", setOf("common", "todo"))),
        StubTool(desc("kanban", "productivity", setOf("kanban"))),
        StubTool(desc("clarify", "communication", setOf("common", "communication"))),
        StubTool(desc("get_current_datetime", "information", setOf("time"))),
        StubTool(desc("search_conversations", "information", setOf("conversation_search"))),
        StubTool(desc("web_search", "information", setOf("web"))),
        StubTool(desc("web_fetch", "information", setOf("web"))),
        StubTool(desc("generate_image", "information", setOf("media_generation"))),
        StubTool(desc("calculator", "productivity", setOf("calculator"))),
        StubTool(desc("memory", "productivity", setOf("user_memory"))),
        StubTool(desc("notes", "productivity", setOf("notes"))),
        StubTool(desc("skill_manager", "productivity", setOf("skills"))),
        StubTool(desc("scheduler", "productivity", setOf("scheduler"))),
        StubTool(desc("delegate", "productivity", setOf("delegate"))),
        StubTool(desc("calendar", "productivity", setOf("calendar"))),
        StubTool(desc("bookmarks", "productivity", setOf("bookmarks"))),
        StubTool(desc("mood", "productivity", setOf("mood"))),
        StubTool(desc("speak", "communication", setOf("voice"))),
        StubTool(desc("notify", "communication", setOf("notification"))),
        StubTool(desc("communication", "communication", setOf("phone"))),
        StubTool(desc("contact_lookup", "communication", setOf("contacts"))),
        StubTool(desc("shell", "device", setOf("shell"))),
        StubTool(desc("termux", "device", setOf("termux"))),
        StubTool(desc("device_settings", "device", setOf("device_settings"))),
        StubTool(desc("alarm", "device", setOf("device_alarm"))),
        StubTool(desc("navigation", "device", setOf("navigation"))),
        StubTool(desc("media_control", "device", setOf("media"))),
        StubTool(desc("device_control", "device", setOf("device_control"))),
        StubTool(desc("app_launch", "device", setOf("app_automation"))),
        StubTool(desc("app_analyze_screen", "device", setOf("app_automation"))),
        StubTool(desc("app_tap", "device", setOf("app_automation"))),
        StubTool(desc("app_swipe", "device", setOf("app_automation"))),
        StubTool(desc("app_type", "device", setOf("app_automation"))),
    )

    private fun makeRegistry(): ToolRegistry {
        val registry = ToolRegistryImpl()
        sampleTools.forEach(registry::register)
        return registry
    }

    private val agents = listOf(
        ConversationalAgent(), ProductivityAgent(), ResearchAgent(),
        DeviceControlAgent(), CreativeAgent(),
    )

    @Test
    fun `all 33 tools are granted to at least one agent`() {
        val registry = makeRegistry()
        for (tool in sampleTools) {
            val toolName = tool.descriptor.name
            val isGranted = agents.any { agent ->
                agent.availableTools(registry).any { it.name == toolName }
            }
            assertTrue("'$toolName' is not granted to any agent", isGranted)
        }
    }

    @Test
    fun `conversational agent exposes expected 31 tools`() {
        val registry = makeRegistry()
        val names = ConversationalAgent().availableTools(registry).map { it.name }.toSet()
        assertEquals(31, names.size)
        assertTrue(names.contains("shell"))
        assertTrue(names.contains("termux"))
        assertTrue(names.contains("generate_image"))
        assertTrue(names.contains("app_launch"))
        assertFalse(names.contains("calendar"))
        assertTrue(names.contains("bookmarks"))
        assertTrue(names.contains("mood"))
    }

    @Test
    fun `productivity agent exposes expected 20 tools`() {
        val registry = makeRegistry()
        val names = ProductivityAgent().availableTools(registry).map { it.name }.toSet()
        assertEquals(20, names.size)
        assertTrue(names.contains("calendar"))
        assertTrue(names.contains("bookmarks"))
        assertTrue(names.contains("mood"))
        assertTrue(names.contains("contact_lookup"))
        assertFalse(names.contains("shell"))
        assertFalse(names.contains("generate_image"))
    }

    @Test
    fun `research agent exposes expected 11 tools`() {
        val registry = makeRegistry()
        val names = ResearchAgent().availableTools(registry).map { it.name }.toSet()
        assertEquals(11, names.size)
        assertTrue(names.contains("bookmarks"))
        assertTrue(names.contains("web_search"))
        assertTrue(names.contains("web_fetch"))
        assertFalse(names.contains("shell"))
        assertFalse(names.contains("app_launch"))
    }

    @Test
    fun `device control agent exposes expected 19 tools including full app automation`() {
        val registry = makeRegistry()
        val names = DeviceControlAgent().availableTools(registry).map { it.name }.toSet()
        assertEquals(19, names.size)
        for (tool in listOf("app_launch", "app_analyze_screen", "app_tap", "app_swipe", "app_type", "shell", "termux")) {
            assertTrue("device-control agent missing '$tool'", names.contains(tool))
        }
    }

    @Test
    fun `creative agent exposes expected 11 tools`() {
        val registry = makeRegistry()
        val names = CreativeAgent().availableTools(registry).map { it.name }.toSet()
        assertEquals(11, names.size)
        assertTrue(names.contains("bookmarks"))
        assertTrue(names.contains("generate_image"))
        assertTrue(names.contains("speak"))
        assertFalse(names.contains("shell"))
        assertFalse(names.contains("app_launch"))
    }

    @Test
    fun `dangerous tool families are not silently widened`() {
        val registry = makeRegistry()
        val dangerousShell = setOf("shell", "termux")
        val dangerousAppAutomation = setOf("app_launch", "app_analyze_screen", "app_tap", "app_swipe", "app_type")

        // Shell/termux only in CONVERSATIONAL and DEVICE_CONTROL
        val productivityTools = ProductivityAgent().availableTools(registry).map { it.name }.toSet()
        val researchTools = ResearchAgent().availableTools(registry).map { it.name }.toSet()
        val creativeTools = CreativeAgent().availableTools(registry).map { it.name }.toSet()

        assertTrue((productivityTools intersect dangerousShell).isEmpty())
        assertTrue((researchTools intersect dangerousShell).isEmpty())
        assertTrue((creativeTools intersect dangerousShell).isEmpty())

        // App automation is available to the general assistant and the dedicated device role.
        assertTrue((productivityTools intersect dangerousAppAutomation).isEmpty())
        assertTrue((researchTools intersect dangerousAppAutomation).isEmpty())
        assertTrue((creativeTools intersect dangerousAppAutomation).isEmpty())
    }

    @Test
    fun `runtime plugin tool registered via InProcessPluginSandbox reaches agent descriptor list`() = runTest {
        val toolRegistry = ToolRegistryImpl()
        sampleTools.forEach(toolRegistry::register)
        val sandbox = InProcessPluginSandbox(toolRegistry)

        val dynamicToolDescriptor = desc(
            name = "custom_weather_lookup",
            category = "information",
            capabilities = setOf("web", "information"),
        )
        val dynamicTool = StubTool(dynamicToolDescriptor)

        val plugin = object : Plugin {
            override val manifest = PluginManifest(
                id = "com.test.weather",
                displayName = "Weather",
                versionCode = 1,
                versionName = "1.0",
                author = "Test",
                signatureFingerprint = "test-sig",
                capabilities = listOf(
                    PluginCapability("weather", "Weather lookup", listOf(dynamicToolDescriptor))
                ),
                permissions = emptyList(),
            )
            override fun tools(): List<Tool> = listOf(dynamicTool)
            override suspend fun onLoad(context: PluginContext) = PluginLifecycleResult.Success
            override suspend fun onSuspend() = PluginLifecycleResult.Success
            override suspend fun onResume() = PluginLifecycleResult.Success
            override suspend fun onUnload() = PluginLifecycleResult.Success
        }

        val testContext = object : PluginContext {
            override fun log(tag: String, level: com.hermes.agent.domain.plugin.LogLevel, message: String, throwable: Throwable?) {}
            override suspend fun hostSetting(key: String): String? = null
            override fun hostAppVersion(): Int = 1
        }

        val result = sandbox.load(plugin, testContext)

        assertTrue(result is PluginLifecycleResult.Success)

        // Verify the dynamic tool now reaches Conversational, Productivity, Research, and Creative agents
        val conversationalTools = toolRegistry.toolsFor(AgentRole.CONVERSATIONAL).map { it.name }
        assertTrue("Conversational agent must reach runtime registered tool", conversationalTools.contains("custom_weather_lookup"))

        val researchTools = toolRegistry.toolsFor(AgentRole.RESEARCH).map { it.name }
        assertTrue("Research agent must reach runtime registered tool", researchTools.contains("custom_weather_lookup"))

        // Unload and verify tool is removed
        sandbox.unload(plugin)
        val postUnloadTools = toolRegistry.toolsFor(AgentRole.CONVERSATIONAL).map { it.name }
        assertFalse(postUnloadTools.contains("custom_weather_lookup"))
    }
}
