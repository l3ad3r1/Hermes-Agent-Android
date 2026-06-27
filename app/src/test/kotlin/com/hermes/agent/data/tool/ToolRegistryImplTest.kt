package com.hermes.agent.data.tool

import com.hermes.agent.data.tool.ToolRegistryImpl

import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import com.hermes.agent.domain.tool.Tool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ToolRegistryImplTest {

    private class StubTool(override val descriptor: ToolDescriptor) : Tool {
        override suspend fun execute(arguments: Map<String, kotlinx.serialization.json.JsonElement>) =
            ToolResult.ok("ok")
    }

    private fun desc(name: String, category: String = "general") = ToolDescriptor(
        name = name,
        description = "stub",
        parameters = listOf(ToolParameter("x", ToolParameterType.STRING, "x")),
        category = category,
    )

    @Test
    fun `register and look up by name`() {
        val registry = ToolRegistryImpl()
        val tool = StubTool(desc("alpha"))
        registry.register(tool)
        assertEquals(tool, registry.byName("alpha"))
    }

    @Test
    fun `byName returns null for unregistered tool`() {
        val registry = ToolRegistryImpl()
        assertNull(registry.byName("nope"))
    }

    @Test
    fun `all returns tools sorted by category then name`() {
        val registry = ToolRegistryImpl()
        registry.register(StubTool(desc("zeta", category = "info")))
        registry.register(StubTool(desc("alpha", category = "info")))
        registry.register(StubTool(desc("gamma", category = "device")))
        val names = registry.all().map { it.descriptor.name }
        assertEquals(listOf("gamma", "alpha", "zeta"), names)
    }

    @Test
    fun `descriptors delegates to all`() {
        val registry = ToolRegistryImpl()
        registry.register(StubTool(desc("a")))
        registry.register(StubTool(desc("b")))
        val names = registry.descriptors().map { it.name }
        assertEquals(listOf("a", "b"), names)
    }

    @Test
    fun `unregister removes the tool`() {
        val registry = ToolRegistryImpl()
        registry.register(StubTool(desc("alpha")))
        registry.unregister("alpha")
        assertNull(registry.byName("alpha"))
    }

    @Test
    fun `re-registering replaces the existing tool`() {
        val registry = ToolRegistryImpl()
        val t1 = StubTool(desc("alpha"))
        val t2 = StubTool(desc("alpha"))
        registry.register(t1)
        registry.register(t2)
        assertEquals(t2, registry.byName("alpha"))
        assertEquals(1, registry.all().size)
    }
}
