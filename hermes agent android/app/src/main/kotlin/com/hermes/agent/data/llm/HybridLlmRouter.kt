package com.hermes.agent.data.llm

import com.hermes.agent.data.settings.SettingsRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routing decision surfaced to the UI so the chat bubble can label
 * responses as on-device or cloud-generated.
 */
sealed class RoutingDecision {
    abstract val provider: LlmProvider

    data class OnDevice(override val provider: LlmProvider) : RoutingDecision()
    data class Cloud(override val provider: LlmProvider, val reason: String) : RoutingDecision()
    data class Unavailable(override val provider: LlmProvider, val reason: String) : RoutingDecision()
}

/**
 * Picks which [LlmProvider] should serve a given request.
 *
 * Implements the dynamic routing strategy described in Section 5.1 of the
 * plan:
 *   1. If on-device is disabled in Settings, skip it.
 *   2. If cloud is disabled (or has no API key), skip it.
 *   3. Classify the request as SIMPLE or COMPLEX via [ComplexityClassifier].
 *   4. SIMPLE → on-device. COMPLEX → cloud (if available), else on-device.
 *   5. If neither is available, return Unavailable with a descriptive reason.
 */
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
        val onDeviceAvail = s.onDeviceEnabled && onDevice.isAvailable()
        val cloudAvail = s.cloudEnabled && cloud.isAvailable()

        if (!onDeviceAvail && !cloudAvail) {
            val reason = buildString {
                append("No LLM provider available. ")
                if (!s.onDeviceEnabled) append("On-device is disabled. ")
                if (s.cloudEnabled && !cloudAvail) append("Cloud is enabled but no API key is set. ")
            }
            return RoutingDecision.Unavailable(onDevice, reason.trim())
        }

        val lastUser = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        val complexity = ComplexityClassifier.classify(lastUser)

        // COMPLEX tasks prefer cloud when available.
        if (complexity.needsCloud && cloudAvail) {
            Timber.tag("LlmRouter").d("Route=cloud (complex), model=${cloud.model}")
            return RoutingDecision.Cloud(cloud, "complex request routed to cloud")
        }

        // Everything else prefers on-device when available.
        if (onDeviceAvail) {
            Timber.tag("LlmRouter").d("Route=on-device (${complexity.name.lowercase()})")
            return RoutingDecision.OnDevice(onDevice)
        }

        // Fallback: only cloud is available.
        Timber.tag("LlmRouter").d("Route=cloud (on-device unavailable)")
        return RoutingDecision.Cloud(cloud, "on-device unavailable, falling back to cloud")
    }
}
