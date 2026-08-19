package com.hermes.agent.data.tools

import com.hermes.agent.data.appagent.AccessibilityNodeResolver
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
class AppTypeTool @Inject constructor(
    private val automation: AppAutomationGateway,
    private val snapshots: ScreenSnapshotStore,
    private val observations: ScreenObservationService,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "app_type",
        description = "Type into an editable element from a specific screen snapshot. Provide the latest snapshot_id, its Tag ID, and the text.",
        parameters = listOf(
            ToolParameter(
                name = "snapshot_id",
                type = ToolParameterType.INTEGER,
                description = "The screen snapshot ID returned with the tag.",
            ),
            ToolParameter(
                name = "tag",
                type = ToolParameterType.INTEGER,
                description = "The numeric Tag ID of the editable element.",
            ),
            ToolParameter(
                name = "text",
                type = ToolParameterType.STRING,
                description = "The text to input.",
            )
        ),
        category = "device",
        capabilities = setOf("device:app_automation", "device"),
        // app_launch is the confirmation boundary; a modal here would replace
        // the target window and invalidate the analyzed tag.
        requiresConfirmation = false,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val snapshotId = (arguments["snapshot_id"] as? JsonPrimitive)?.longOrNull
            ?: return ToolResult.error("Missing required parameter: snapshot_id")
        val tagId = (arguments["tag"] as? JsonPrimitive)?.intOrNull
            ?: return ToolResult.error("Missing required parameter: tag")
        val textToType = (arguments["text"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolResult.error("Missing required parameter: text")

        val rootNode = automation.activeWindowRoot()
            ?: return ToolResult.error("App control is unavailable or the screen is locked.")

        val targetNodeInfo = when (val lookup = snapshots.resolve(snapshotId, tagId, rootNode)) {
            is SnapshotLookup.Found -> requireNotNull(lookup.node)
            is SnapshotLookup.Rejected -> return ToolResult.error(lookup.message)
        }

        if (!targetNodeInfo.isEditable) {
            return ToolResult.error("Element with tag $tagId is not editable.")
        }

        val actualNode = AccessibilityNodeResolver.find(rootNode, targetNodeInfo)
        if (actualNode == null) {
            return ToolResult.error("Could not locate the actual UI node for tag $tagId.")
        }

        val success = automation.setText(actualNode, textToType)

        return if (success) {
            snapshots.consume(snapshotId)
            val refresh = observations.capture(
                settleDelayMs = POST_ACTION_SETTLE_MS,
                redactedText = textToType,
            )
            // Never echo typed content: it may contain passwords or other secrets.
            val output = buildString {
                appendLine("Entered text into tag $tagId from snapshot $snapshotId.")
                when (refresh) {
                    is ScreenObservation.Captured -> append(refresh.modelText)
                    is ScreenObservation.Unavailable -> append(
                        "The text action was accepted, but the next screen could not be analyzed: ${refresh.message}",
                    )
                }
            }
            ToolResult.ok(output, System.currentTimeMillis() - start)
        } else {
            ToolResult.error("Failed to type text into element $tagId.", System.currentTimeMillis() - start)
        }
    }

    private companion object {
        const val POST_ACTION_SETTLE_MS = 350L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppTypeToolModule {
    @Binds
    @IntoSet
    abstract fun bindAppTypeTool(tool: AppTypeTool): Tool
}
