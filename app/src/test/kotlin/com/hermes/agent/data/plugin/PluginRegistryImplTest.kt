package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.PermissionType
import com.hermes.agent.domain.plugin.Plugin
import com.hermes.agent.domain.plugin.PluginCapability
import com.hermes.agent.domain.plugin.PluginContext
import com.hermes.agent.domain.plugin.PluginLifecycleResult
import com.hermes.agent.domain.plugin.PluginManifest
import com.hermes.agent.domain.plugin.PluginPermission
import com.hermes.agent.domain.plugin.PluginState
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.domain.tool.ToolResult
import com.hermes.agent.data.tool.ToolRegistryImpl
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRegistryImplTest {

    private val dummyContext = object : PluginContext {
        override fun log(tag: String, level: com.hermes.agent.domain.plugin.LogLevel, message: String, throwable: Throwable?) {}
        override suspend fun hostSetting(key: String): String? = null
        override fun hostAppVersion(): Int = 1
    }

    private fun makePlugin(id: String): Plugin = object : Plugin {
        override val manifest = PluginManifest(
            id = id,
            displayName = id,
            versionCode = 1,
            versionName = "1.0",
            author = "test",
            signatureFingerprint = "test",
            capabilities = listOf(
                PluginCapability(
                    name = "cap_$id",
                    description = "test capability",
                    toolDescriptors = listOf(
                        ToolDescriptor(
                            name = "tool_$id",
                            description = "test tool",
                            parameters = emptyList(),
                        ),
                    ),
                ),
            ),
            permissions = listOf(PluginPermission(PermissionType.NETWORK, "test")),
        )

        override fun tools(): List<Tool> = listOf(object : Tool {
            override val descriptor = manifest.capabilities.first().toolDescriptors.first()
            override suspend fun execute(arguments: Map<String, JsonElement>) = ToolResult.ok("ok")
        })

        override suspend fun onLoad(context: PluginContext) = PluginLifecycleResult.Success
        override suspend fun onSuspend() = PluginLifecycleResult.Success
        override suspend fun onResume() = PluginLifecycleResult.Success
        override suspend fun onUnload() = PluginLifecycleResult.Success
    }

    private fun makeRegistry(): Pair<PluginRegistryImpl, ToolRegistryImpl> {
        val toolRegistry = ToolRegistryImpl()
        val sandbox = InProcessPluginSandbox(toolRegistry)
        val grpc = GrpcPluginSandbox()
        val monitor = PluginResourceMonitor()
        val registry = PluginRegistryImpl(sandbox, grpc, dummyContext, monitor)
        return registry to toolRegistry
    }

    @Test
    fun `registerFirstParty adds plugin in INSTALLED state`() = runTest {
        val (registry, _) = makeRegistry()
        val plugin = makePlugin("test.one")
        registry.registerFirstParty(plugin)

        val plugins = registry.observePlugins().value
        assertEquals(1, plugins.size)
        assertEquals(PluginState.INSTALLED, plugins[0].state)
    }

    @Test
    fun `activate moves plugin to ACTIVE state and registers its tools`() = runTest {
        val (registry, toolRegistry) = makeRegistry()
        val plugin = makePlugin("test.two")
        registry.registerFirstParty(plugin)

        val result = registry.activate("test.two")
        assertTrue(result is PluginLifecycleResult.Success)

        val activated = registry.observePlugins().value.first { it.manifest.id == "test.two" }
        assertEquals(PluginState.ACTIVE, activated.state)
        assertNotNull(activated.loadedAt)

        // Tool should be registered with the global ToolRegistry.
        assertNotNull(toolRegistry.byName("tool_test.two"))
    }

    @Test
    fun `suspend_ moves plugin to SUSPENDED state`() = runTest {
        val (registry, _) = makeRegistry()
        registry.registerFirstParty(makePlugin("test.three"))
        registry.activate("test.three")

        registry.suspend_("test.three")

        val suspended = registry.observePlugins().value.first { it.manifest.id == "test.three" }
        assertEquals(PluginState.SUSPENDED, suspended.state)
    }

    @Test
    fun `uninstall removes plugin and unloads its tools`() = runTest {
        val (registry, toolRegistry) = makeRegistry()
        registry.registerFirstParty(makePlugin("test.four"))
        registry.activate("test.four")
        assertNotNull(toolRegistry.byName("tool_test.four"))

        registry.uninstall("test.four")

        assertTrue(registry.observePlugins().value.isEmpty())
        assertNull(toolRegistry.byName("tool_test.four"))
    }

    @Test
    fun `byId returns null for unknown plugin`() = runTest {
        val (registry, _) = makeRegistry()
        assertNull(registry.byId("does.not.exist"))
    }

    @Test
    fun `activeToolDescriptors returns tools from ACTIVE plugins only`() = runTest {
        val (registry, _) = makeRegistry()
        registry.registerFirstParty(makePlugin("test.five"))
        registry.registerFirstParty(makePlugin("test.six"))

        // Nothing active yet.
        assertTrue(registry.activeToolDescriptors().isEmpty())

        registry.activate("test.five")
        assertEquals(1, registry.activeToolDescriptors().size)

        registry.activate("test.six")
        assertEquals(2, registry.activeToolDescriptors().size)
    }
}
