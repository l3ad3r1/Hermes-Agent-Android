package com.hermes.agent.data.tools

import android.app.NotificationManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.Settings
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
class DeviceControlTool @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : Tool {
    override val descriptor = ToolDescriptor(
        name = "device_control",
        description = "Control flashlight, audio volume, ringer mode, Do Not Disturb, and screen brightness.",
        parameters = listOf(
            ToolParameter("action", ToolParameterType.STRING, "Device action.", enumValues = listOf("flashlight", "set_volume", "set_ringer_mode", "set_dnd", "set_brightness")),
            ToolParameter("enabled", ToolParameterType.BOOLEAN, "Whether a switch-like feature is enabled.", required = false),
            ToolParameter("level", ToolParameterType.INTEGER, "Volume level or brightness from 0 to 255.", required = false),
            ToolParameter("stream", ToolParameterType.STRING, "Audio stream.", required = false, enumValues = listOf("music", "ring", "notification", "alarm", "system")),
            ToolParameter("mode", ToolParameterType.STRING, "Ringer mode.", required = false, enumValues = listOf("normal", "vibrate", "silent")),
            ToolParameter("auto", ToolParameterType.BOOLEAN, "Enable automatic brightness.", required = false),
        ),
        category = "device",
        capabilities = setOf("device:control", "device"),
        requiresConfirmation = true,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        return when (val action = arguments.string("action")) {
            "flashlight" -> setFlashlight(arguments.bool("enabled") ?: return ToolResult.error("enabled is required"))
            "set_volume" -> setVolume(arguments.string("stream") ?: "music", arguments.int("level") ?: return ToolResult.error("level is required"))
            "set_ringer_mode" -> setRinger(arguments.string("mode") ?: return ToolResult.error("mode is required"))
            "set_dnd" -> setDnd(arguments.bool("enabled") ?: return ToolResult.error("enabled is required"))
            "set_brightness" -> setBrightness(arguments.int("level"), arguments.bool("auto") ?: false)
            null -> ToolResult.error("missing required parameter: action")
            else -> ToolResult.error("unknown device action: $action")
        }
    }

    private fun setFlashlight(enabled: Boolean): ToolResult = runCatching {
        val manager = context.getSystemService(CameraManager::class.java) ?: error("Camera service unavailable")
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            val c = manager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: error("No flashlight is available")
        manager.setTorchMode(cameraId, enabled)
        ToolResult.ok("Flashlight turned ${if (enabled) "on" else "off"}")
    }.getOrElse { ToolResult.error("Could not control flashlight: ${it.message}") }

    private fun setVolume(stream: String, requested: Int): ToolResult {
        val audio = context.getSystemService(AudioManager::class.java) ?: return ToolResult.error("Audio service unavailable")
        val streamType = when (stream) {
            "ring" -> AudioManager.STREAM_RING; "notification" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM; "system" -> AudioManager.STREAM_SYSTEM
            else -> AudioManager.STREAM_MUSIC
        }
        val level = requested.coerceIn(0, audio.getStreamMaxVolume(streamType))
        audio.setStreamVolume(streamType, level, 0)
        return ToolResult.ok("$stream volume set to $level")
    }

    private fun setRinger(mode: String): ToolResult = runCatching {
        val audio = context.getSystemService(AudioManager::class.java) ?: error("Audio service unavailable")
        audio.ringerMode = when (mode) {
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> return ToolResult.error("invalid ringer mode: $mode")
        }
        ToolResult.ok("Ringer mode set to $mode")
    }.getOrElse { ToolResult.error("Could not change ringer mode: ${it.message}") }

    private fun setDnd(enabled: Boolean): ToolResult {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return ToolResult.error("Notification service unavailable")
        if (!manager.isNotificationPolicyAccessGranted) {
            return ToolResult.error("Do Not Disturb access is not granted in Android settings")
        }
        manager.setInterruptionFilter(if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return ToolResult.ok("Do Not Disturb turned ${if (enabled) "on" else "off"}")
    }

    private fun setBrightness(level: Int?, auto: Boolean): ToolResult {
        if (!Settings.System.canWrite(context)) return ToolResult.error("Modify system settings access is not granted")
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        if (!auto) Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (level ?: return ToolResult.error("level is required")).coerceIn(0, 255))
        return ToolResult.ok(if (auto) "Automatic brightness enabled" else "Brightness set to ${level!!.coerceIn(0, 255)}")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceControlToolModule {
    @Binds
    @IntoSet
    abstract fun bindDeviceControlTool(tool: DeviceControlTool): Tool
}
