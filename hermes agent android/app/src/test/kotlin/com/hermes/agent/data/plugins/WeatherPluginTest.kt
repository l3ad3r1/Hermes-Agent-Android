package com.hermes.agent.data.plugins

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherPluginTest {

    private val plugin = WeatherPlugin()

    @Test
    fun `manifest declares weather_lookup capability with weather_get tool`() {
        val cap = plugin.manifest.capabilities.firstOrNull { it.name == "weather_lookup" }
        assertTrue("expected weather_lookup capability", cap != null)
        assertEquals("weather_get", cap!!.toolDescriptors.first().name)
    }

    @Test
    fun `manifest declares NETWORK permission`() {
        assertTrue(plugin.manifest.permissions.any { it.type == com.hermes.agent.domain.plugin.PermissionType.NETWORK })
    }

    @Test
    fun `onLoad returns Success`() = runTest {
        val result = plugin.onLoad(object : com.hermes.agent.domain.plugin.PluginContext {
            override fun log(tag: String, level: com.hermes.agent.domain.plugin.LogLevel, message: String, throwable: Throwable?) {}
            override suspend fun hostSetting(key: String): String? = null
            override fun hostAppVersion(): Int = 1
        })
        assertTrue(result is com.hermes.agent.domain.plugin.PluginLifecycleResult.Success)
    }

    @Test
    fun `tools returns the weather_get tool`() {
        val tools = plugin.tools()
        assertEquals(1, tools.size)
        assertEquals("weather_get", tools[0].descriptor.name)
    }

    @Test
    fun `weather_get tool returns deterministic mock data for same city`() = runTest {
        val tool = plugin.tools().first()
        val r1 = tool.execute(mapOf("city" to JsonPrimitive("Tokyo")))
        val r2 = tool.execute(mapOf("city" to JsonPrimitive("Tokyo")))
        assertTrue(r1.success)
        assertEquals(r1.output, r2.output) // deterministic per city hashCode
        assertTrue(r1.output.contains("Tokyo"))
    }

    @Test
    fun `weather_get tool errors on missing city`() = runTest {
        val tool = plugin.tools().first()
        val r = tool.execute(emptyMap())
        assertTrue(!r.success)
        assertTrue(r.errorMessage!!.contains("city"))
    }
}
