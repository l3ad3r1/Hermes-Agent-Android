package com.hermes.agent.data.tools

import com.hermes.agent.data.appagent.ScreenObservation
import com.hermes.agent.data.appagent.ScreenObservationService
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppAnalyzeScreenTool @Inject constructor(
    private val observations: ScreenObservationService,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "app_analyze_screen",
        description = "Analyze the current screen to find actionable UI elements. Returns a list of elements with numeric tags that can be used for tapping or typing.",
        parameters = emptyList(),
        category = "device",
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()

        return when (val observation = observations.capture()) {
            is ScreenObservation.Captured -> ToolResult.ok(
                observation.modelText,
                System.currentTimeMillis() - start,
            )
            is ScreenObservation.Unavailable -> ToolResult.error(
                observation.message,
                System.currentTimeMillis() - start,
            )
        }
    }
}
