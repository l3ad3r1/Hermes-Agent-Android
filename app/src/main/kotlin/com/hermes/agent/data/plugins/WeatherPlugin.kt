package com.hermes.agent.data.plugins

import com.hermes.agent.domain.plugin.Plugin
import com.hermes.agent.domain.plugin.PluginCapability
import com.hermes.agent.domain.plugin.PluginContext
import com.hermes.agent.domain.plugin.PluginLifecycleResult
import com.hermes.agent.domain.plugin.PluginManifest
import com.hermes.agent.domain.plugin.PluginPermission
import com.hermes.agent.domain.plugin.PermissionType
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Weather plugin — Phase 3 sample.
 *
 * Advertises a single `weather_get` tool that returns the current
 * weather for a city. Phase 3 returns canned data so the plugin
 * lifecycle (load → tool call → unload) can be exercised end-to-end.
 * Phase 3.x will swap the body for a real Retrofit call to the
 * National Weather Service or Open-Meteo API (both free, no key).
 *
 * Permissions: NETWORK (for the real implementation).
 */
@Singleton
class WeatherPlugin @Inject constructor() : Plugin {

    override val manifest = PluginManifest(
        id = "com.hermes.plugin.weather",
        displayName = "Weather",
        versionCode = 1,
        versionName = "1.0.0",
        author = "Jeeves Team",
        signatureFingerprint = "(in-process, no signature required)",
        capabilities = listOf(
            PluginCapability(
                name = "weather_lookup",
                description = "Look up current weather conditions for a city.",
                toolDescriptors = listOf(
                    ToolDescriptor(
                        name = "weather_get",
                        description = "Get current temperature, conditions, and humidity for a city.",
                        parameters = listOf(
                            ToolParameter(
                                name = "city",
                                type = ToolParameterType.STRING,
                                description = "City name, e.g. 'Tokyo' or 'San Francisco'.",
                            ),
                            ToolParameter(
                                name = "units",
                                type = ToolParameterType.STRING,
                                description = "Temperature units: 'celsius' or 'fahrenheit'.",
                                required = false,
                                enumValues = listOf("celsius", "fahrenheit"),
                            ),
                        ),
                        category = "information",
                    ),
                ),
            ),
        ),
        permissions = listOf(
            PluginPermission(
                type = PermissionType.NETWORK,
                rationale = "Fetches live weather data from a public API.",
            ),
        ),
    )

    private var loaded = false

    override fun tools(): List<Tool> = listOf(weatherTool)

    override suspend fun onLoad(context: PluginContext): PluginLifecycleResult {
        context.log("Weather", com.hermes.agent.domain.plugin.LogLevel.INFO, "loading")
        loaded = true
        return PluginLifecycleResult.Success
    }

    override suspend fun onSuspend(): PluginLifecycleResult {
        loaded = false
        return PluginLifecycleResult.Success
    }

    override suspend fun onResume(): PluginLifecycleResult {
        loaded = true
        return PluginLifecycleResult.Success
    }

    override suspend fun onUnload(): PluginLifecycleResult {
        loaded = false
        return PluginLifecycleResult.Success
    }

    private val weatherTool = object : Tool {
        override val descriptor = manifest.capabilities.first().toolDescriptors.first()

        override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
            val city = arguments["city"]?.extractString()
                ?: return ToolResult.error("missing required parameter: city")
            val units = arguments["units"]?.extractString() ?: "celsius"

            // Phase 3 mock — deterministic per city name so demos are reproducible.
            val r = Random(city.hashCode())
            val tempC = r.nextInt(-5, 40)
            val temp = if (units == "fahrenheit") tempC * 9 / 5 + 32 else tempC
            val conditions = listOf("sunny", "cloudy", "rainy", "snowy", "windy")[r.nextInt(5)]
            val humidity = r.nextInt(20, 95)

            return ToolResult.ok(
                output = "Weather in $city: ${temp}${if (units == "celsius") "°C" else "°F"}, " +
                    "$conditions, humidity ${humidity}%.\n" +
                    "[Phase 3 mock — real implementation will hit Open-Meteo in Phase 3.x.]",
            )
        }

        private fun JsonElement.extractString(): String? =
            (this as? JsonPrimitive)?.contentOrNull
    }
}
