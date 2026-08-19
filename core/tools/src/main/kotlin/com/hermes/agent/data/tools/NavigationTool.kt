package com.hermes.agent.data.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hermes.agent.domain.tool.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import com.hermes.agent.domain.tool.Tool

@Singleton
class NavigationTool @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : Tool {
    override val descriptor = ToolDescriptor(
        name = "navigation",
        description = "Navigate to a destination, search nearby places, or display a location on a map.",
        parameters = listOf(
            ToolParameter("action", ToolParameterType.STRING, "Navigation action.", enumValues = listOf("navigate", "search_nearby", "show_map")),
            ToolParameter("query", ToolParameterType.STRING, "Destination, place, address, or nearby search.", required = false),
            ToolParameter("mode", ToolParameterType.STRING, "Travel mode.", required = false, enumValues = listOf("driving", "walking", "bicycling", "transit")),
        ),
        category = "device",
        capabilities = setOf("device:navigation", "device"),
        requiresConfirmation = true,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val action = arguments.string("action") ?: return ToolResult.error("missing required parameter: action")
        val query = arguments.string("query")
        val uri = when (action) {
            "navigate" -> {
                val destination = query ?: return ToolResult.error("query is required for navigation")
                val mode = when (arguments.string("mode")) {
                    "walking" -> "w"; "bicycling" -> "b"; "transit" -> "r"; else -> "d"
                }
                Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$mode")
            }
            "search_nearby", "show_map" -> Uri.parse("geo:0,0?q=${Uri.encode(query.orEmpty())}")
            else -> return ToolResult.error("unknown navigation action: $action")
        }
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult.ok("Opened navigation for ${query.orEmpty()}")
        }.getOrElse { ToolResult.error("No compatible maps app is available: ${it.message}") }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NavigationToolModule {
    @Binds
    @IntoSet
    abstract fun bindNavigationTool(tool: NavigationTool): Tool
}
