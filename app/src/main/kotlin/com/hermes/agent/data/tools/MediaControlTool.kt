package com.hermes.agent.data.tools

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.MediaStore
import android.view.KeyEvent
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
class MediaControlTool @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : Tool {
    override val descriptor = ToolDescriptor(
        name = "media_control",
        description = "Play or pause media, skip tracks, or ask an installed music app to play a search.",
        parameters = listOf(
            ToolParameter("action", ToolParameterType.STRING, "Media action.", enumValues = listOf("play_pause", "next", "previous", "play_search")),
            ToolParameter("query", ToolParameterType.STRING, "Song, artist, album, or playlist to play.", required = false),
        ),
        category = "device",
        capabilities = setOf("device:media", "device"),
        requiresConfirmation = true,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val action = arguments.string("action") ?: return ToolResult.error("missing required parameter: action")
        if (action == "play_search") {
            val query = arguments.string("query") ?: return ToolResult.error("query is required")
            return runCatching {
                context.startActivity(
                    Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                        .putExtra(SearchManager.QUERY, query)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                ToolResult.ok("Requested music: $query")
            }.getOrElse { ToolResult.error("No compatible music app is available: ${it.message}") }
        }

        val keyCode = when (action) {
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return ToolResult.error("unknown media action: $action")
        }
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return ToolResult.error("Audio service is unavailable")
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return ToolResult.ok("Media action completed: $action")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaControlToolModule {
    @Binds
    @IntoSet
    abstract fun bindMediaControlTool(tool: MediaControlTool): Tool
}
