package com.hermes.agent.data.tools

import com.hermes.agent.data.appagent.AppAutomationGateway
import com.hermes.agent.data.appagent.ScreenObservation
import com.hermes.agent.data.appagent.ScreenObservationService
import com.hermes.agent.data.appagent.ScreenSnapshotStore
import com.hermes.agent.data.appagent.SnapshotLookup
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Singleton
class AppSwipeTool @Inject constructor(
    private val automation: AppAutomationGateway,
    private val snapshots: ScreenSnapshotStore,
    private val observations: ScreenObservationService,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "app_swipe",
        description = "Swipe the screen represented by the latest screen snapshot.",
        parameters = listOf(
            ToolParameter(
                name = "snapshot_id",
                type = ToolParameterType.INTEGER,
                description = "The latest screen snapshot ID.",
            ),
            ToolParameter(
                name = "direction",
                type = ToolParameterType.STRING,
                description = "The direction to swipe. Must be 'up', 'down', 'left', or 'right'.",
                enumValues = listOf("up", "down", "left", "right")
            )
        ),
        category = "device",
        capabilities = setOf("device:app_automation", "device"),
        // Preserve the launched app as the active accessibility window.
        requiresConfirmation = false,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val snapshotId = (arguments["snapshot_id"] as? JsonPrimitive)?.longOrNull
            ?: return ToolResult.error("Missing required parameter: snapshot_id")
        val direction = (arguments["direction"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolResult.error("Missing required parameter: direction")

        val rootNode = automation.activeWindowRoot()
            ?: return ToolResult.error("App control is unavailable or the screen is locked.")
        when (val validation = snapshots.validate(snapshotId, rootNode)) {
            is SnapshotLookup.Rejected -> return ToolResult.error(validation.message)
            is SnapshotLookup.Found -> Unit
        }

        val bounds = automation.screenBounds()
            ?: return ToolResult.error("Could not determine the current screen dimensions.")
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val horizontalDistance = bounds.width() * SWIPE_DISTANCE_FRACTION
        val verticalDistance = bounds.height() * SWIPE_DISTANCE_FRACTION

        val (startX, startY, endX, endY) = when (direction.lowercase()) {
            "up" -> listOf(centerX, centerY + verticalDistance, centerX, centerY - verticalDistance)
            "down" -> listOf(centerX, centerY - verticalDistance, centerX, centerY + verticalDistance)
            "left" -> listOf(centerX + horizontalDistance, centerY, centerX - horizontalDistance, centerY)
            "right" -> listOf(centerX - horizontalDistance, centerY, centerX + horizontalDistance, centerY)
            else -> return ToolResult.error("Invalid direction. Must be up, down, left, or right.")
        }

        val success = automation.dispatchSwipe(startX, startY, endX, endY)

        return if (success) {
            snapshots.consume(snapshotId)
            val refresh = observations.capture(POST_ACTION_SETTLE_MS)
            val output = buildString {
                appendLine("Swiped $direction on snapshot $snapshotId.")
                when (refresh) {
                    is ScreenObservation.Captured -> append(refresh.modelText)
                    is ScreenObservation.Unavailable -> append(
                        "The swipe was accepted, but the next screen could not be analyzed: ${refresh.message}",
                    )
                }
            }
            ToolResult.ok(output, System.currentTimeMillis() - start)
        } else {
            ToolResult.error("Failed to swipe $direction.", System.currentTimeMillis() - start)
        }
    }

    private companion object {
        const val SWIPE_DISTANCE_FRACTION = 0.3f
        const val POST_ACTION_SETTLE_MS = 350L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppSwipeToolModule {
    @Binds
    @IntoSet
    abstract fun bindAppSwipeTool(tool: AppSwipeTool): Tool
}
