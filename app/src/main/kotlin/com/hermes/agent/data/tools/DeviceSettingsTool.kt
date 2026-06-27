package com.hermes.agent.data.tools

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
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

/**
 * Reads and (with user confirmation) modifies device settings — currently
 * screen brightness and media volume.
 *
 * Phase 2 deliberately ships only read-only operations plus the two
 * side-effecting ones wrapped behind `requiresConfirmation = true`. The
 * orchestrator's confirmation dialog is the gate; the tool itself assumes
 * it has permission once invoked.
 *
 * Phase 3 will expand this to Wi-Fi, Bluetooth, DND, airplane mode, and
 * app launching.
 */
@Singleton
class DeviceSettingsTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "device_settings",
        description = "Read or change device settings: screen brightness (0–255) and media " +
            "volume (0–15). Use action='get' to read the current value, action='set' to change it.",
        parameters = listOf(
            ToolParameter(
                name = "setting",
                type = ToolParameterType.STRING,
                description = "Which setting to operate on.",
                enumValues = listOf("brightness", "media_volume"),
            ),
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "'get' to read the current value, 'set' to change it.",
                enumValues = listOf("get", "set"),
            ),
            ToolParameter(
                name = "value",
                type = ToolParameterType.INTEGER,
                description = "New value when action='set'. Ignored for action='get'.",
                required = false,
            ),
        ),
        category = "device",
        requiresConfirmation = true,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val setting = arguments["setting"]?.extractString()
            ?: return ToolResult.error("missing required parameter: setting")
        val action = arguments["action"]?.extractString()
            ?: return ToolResult.error("missing required parameter: action")

        return when (setting) {
            "brightness" -> handleBrightness(action, arguments["value"]?.extractString()?.toIntOrNull())
            "media_volume" -> handleVolume(action, arguments["value"]?.extractString()?.toIntOrNull())
            else -> ToolResult.error("unknown setting: $setting")
        }.copy(executionMs = System.currentTimeMillis() - start)
    }

    private fun handleBrightness(action: String, value: Int?): ToolResult {
        return when (action) {
            "get" -> {
                val mode = Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
                val current = Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    0,
                )
                ToolResult.ok(
                    output = "brightness=$current (mode=${if (mode == 0) "manual" else "auto"})"
                )
            }
            "set" -> {
                val v = value ?: return ToolResult.error("missing 'value' for action=set")
                val clamped = v.coerceIn(0, 255)
                val ok = Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    clamped,
                )
                if (ok) ToolResult.ok("brightness set to $clamped")
                else ToolResult.error("could not set brightness (permission denied?)")
            }
            else -> ToolResult.error("unknown action: $action")
        }
    }

    private fun handleVolume(action: String, value: Int?): ToolResult {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return ToolResult.error("AudioManager unavailable")
        return when (action) {
            "get" -> {
                val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                ToolResult.ok("media_volume=$current (max=$max)")
            }
            "set" -> {
                val v = value ?: return ToolResult.error("missing 'value' for action=set")
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val clamped = v.coerceIn(0, max)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
                ToolResult.ok("media_volume set to $clamped")
            }
            else -> ToolResult.error("unknown action: $action")
        }
    }

    private fun JsonElement.extractString(): String? =
        (this as? JsonPrimitive)?.contentOrNull
}
