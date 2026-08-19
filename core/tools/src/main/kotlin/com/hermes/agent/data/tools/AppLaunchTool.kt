package com.hermes.agent.data.tools

import android.content.Context
import android.content.Intent
import com.hermes.agent.data.appagent.ScreenSnapshotStore
import com.hermes.agent.data.appagent.AppInteractionSession
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Launches an installed app before the accessibility tools operate on it. */
@Singleton
class AppLaunchTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val snapshots: ScreenSnapshotStore,
    private val interactionSession: AppInteractionSession,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "app_launch",
        description = "Launch an installed Android app by package name. Call this before " +
            "app_analyze_screen when the requested app is not already visible.",
        parameters = listOf(
            ToolParameter(
                name = "package_name",
                type = ToolParameterType.STRING,
                description = "Android package name, for example com.google.android.calendar.",
            ),
        ),
        category = "device",
        capabilities = setOf("device:app_automation", "device"),
        requiresConfirmation = true,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val packageName = (arguments["package_name"] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.error("missing required parameter: package_name")

        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ToolResult.error("No launchable app is installed for package $packageName.")

        return runCatching {
            snapshots.clear()
            interactionSession.clear()
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            interactionSession.authorize(packageName)
            ToolResult.ok(
                output = "Launched $packageName. Analyze the new screen before interacting.",
                executionMs = System.currentTimeMillis() - start,
            )
        }.getOrElse { error ->
            ToolResult.error(
                message = "Failed to launch $packageName: ${error.message ?: error.javaClass.simpleName}",
                executionMs = System.currentTimeMillis() - start,
            )
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppLaunchToolModule {
    @Binds
    @IntoSet
    abstract fun bindAppLaunchTool(tool: AppLaunchTool): Tool
}
