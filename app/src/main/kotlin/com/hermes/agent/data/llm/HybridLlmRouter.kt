package com.hermes.agent.data.llm

import com.hermes.agent.data.settings.SettingsRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed class RoutingDecision {
    abstract val provider: LlmProvider

    data class OnDevice(override val provider: LlmProvider) : RoutingDecision()
    data class Cloud(override val provider: LlmProvider, val reason: String) : RoutingDecision()
    data class Unavailable(override val provider: LlmProvider, val reason: String) : RoutingDecision()
}

interface LlmRouter {
    suspend fun route(messages: List<LlmMessage>): RoutingDecision
}

@Singleton
class HybridLlmRouter @Inject constructor(
    private val onDevice: OnDeviceLlmProvider,
    private val cloud: CloudLlmProvider,
    private val settings: SettingsRepository,
) : LlmRouter {

    override suspend fun route(messages: List<LlmMessage>): RoutingDecision {
        val s = settings.current()

        // Prefer on-device when enabled and model is available.
        if (s.onDeviceEnabled && onDevice.isAvailable()) {
            Timber.tag("LlmRouter").d("Route=on-device")
            return RoutingDecision.OnDevice(onDevice)
        }

        // Fall back to cloud.
        if (s.cloudEnabled && cloud.isAvailable()) {
            Timber.tag("LlmRouter").d("Route=cloud, model=${cloud.model}")
            return RoutingDecision.Cloud(cloud, "cloud provider")
        }

        val reason = buildString {
            if (!s.onDeviceEnabled && !s.cloudEnabled) {
                append("No LLM provider enabled. Enable Cloud or On-Device in Settings.")
            } else if (s.onDeviceEnabled && !onDevice.isAvailable()) {
                append("On-device LLM enabled but not ready. Check model path in Settings.")
                if (s.cloudEnabled) append(" Cloud is also unavailable (no API key).")
            } else {
                append("Cloud is enabled but no API key is set. Add one in Settings.")
            }
        }
        return RoutingDecision.Unavailable(cloud, reason)
    }
}
