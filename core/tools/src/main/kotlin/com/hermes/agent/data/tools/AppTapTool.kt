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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Singleton
class AppTapTool @Inject constructor(
    private val automation: AppAutomationGateway,
    private val snapshots: ScreenSnapshotStore,
    private val observations: ScreenObservationService,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "app_tap",
        description = "Tap an element from a specific screen snapshot. Provide both the snapshot_id and Tag ID returned by the latest screen observation.",
        parameters = listOf(
            ToolParameter(
                name = "snapshot_id",
                type = ToolParameterType.INTEGER,
                description = "The screen snapshot ID returned with the tag.",
            ),
            ToolParameter(
                name = "tag",
                type = ToolParameterType.INTEGER,
                description = "The numeric Tag ID of the element to tap.",
            )
        ),
        category = "device",
        capabilities = setOf("device:app_automation", "device"),
        // The user approves the target app once through app_launch. Keeping the
        // automation screen visible is required for tag-based interaction.
        requiresConfirmation = false,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val snapshotId = (arguments["snapshot_id"] as? JsonPrimitive)?.longOrNull
            ?: return ToolResult.error("Missing required parameter: snapshot_id")
        val tagId = (arguments["tag"] as? JsonPrimitive)?.intOrNull
            ?: return ToolResult.error("Missing required parameter: tag")

        val rootNode = automation.activeWindowRoot()
            ?: return ToolResult.error("App control is unavailable or the screen is locked.")

        val targetNode = when (val lookup = snapshots.resolve(snapshotId, tagId, rootNode)) {
            is SnapshotLookup.Found -> requireNotNull(lookup.node)
            is SnapshotLookup.Rejected -> return ToolResult.error(lookup.message)
        }

        val centerX = targetNode.bounds.centerX().toFloat()
        val centerY = targetNode.bounds.centerY().toFloat()

        val success = automation.dispatchTap(centerX, centerY)

        return if (success) {
            snapshots.consume(snapshotId)
            val refresh = observations.capture(POST_ACTION_SETTLE_MS)
            val output = buildString {
                appendLine("Tapped tag $tagId from snapshot $snapshotId at ($centerX, $centerY).")
                when (refresh) {
                    is ScreenObservation.Captured -> append(refresh.modelText)
                    is ScreenObservation.Unavailable -> append(
                        "The action was accepted, but the next screen could not be analyzed: ${refresh.message}",
                    )
                }
            }
            ToolResult.ok(output, System.currentTimeMillis() - start)
        } else {
            ToolResult.error("Failed to tap element $tagId.", System.currentTimeMillis() - start)
        }
    }

    private companion object {
        const val POST_ACTION_SETTLE_MS = 350L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppTapToolModule {
    @Binds
    @IntoSet
    abstract fun bindAppTapTool(tool: AppTapTool): Tool
}
