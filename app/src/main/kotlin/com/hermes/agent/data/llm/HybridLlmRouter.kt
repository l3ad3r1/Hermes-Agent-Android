package com.hermes.agent.data.llm

import com.hermes.agent.data.settings.SettingsRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

sealed class RoutingDecision {
    abstract val provider: LlmProvider

    data class Ready(override val provider: LlmProvider, val reason: String) : RoutingDecision()
    data class Unavailable(override val provider: LlmProvider, val reason: String) : RoutingDecision()
}

interface LlmRouter {
    suspend fun route(messages: List<LlmMessage>): RoutingDecision
}

@Singleton
class HybridLlmRouter @Inject constructor(
    private val cloud: CloudLlmProvider,
    @Named("cloudAux") private val specialised: CloudLlmProvider,
    private val local: LocalLlmProvider,
    private val settings: SettingsRepository,
) : LlmRouter {

    override suspend fun route(messages: List<LlmMessage>): RoutingDecision {
        val s = settings.current()
        val useCloud = s.cloudEnabled && cloud.isAvailable()

        if (!useCloud) {
            if (local.isAvailable()) {
                return RoutingDecision.Ready(local, "Cloud disabled or unavailable; falling back to local model")
            }
            val reason = if (!s.cloudEnabled) {
                "Cloud is disabled and local model is not downloaded. Configure a Cloud LLM or download the local model in Settings."
            } else {
                "Cloud is enabled but no API key is set, and local model is not downloaded. Add one in Settings."
            }
            return RoutingDecision.Unavailable(cloud, reason)
        }

        // Two cloud models, one per task class: everyday / simple requests go to
        // the primary model (settings.cloudModel) — meant to be the faster,
        // general-purpose model — while complex / reasoning-heavy requests go to
        // the specialist model (settings.auxModel), typically a larger reasoning
        // model. If the specialist is unavailable we fall back to the primary.
        val lastUserMessage = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        return when (ComplexityClassifier.classify(lastUserMessage)) {
            RequestComplexity.COMPLEX -> {
                val target = if (specialised.isAvailable()) specialised else cloud
                Timber.tag("LlmRouter").d("Route=cloud/specialist, model=${target.model}")
                RoutingDecision.Ready(withLocalFailover(target), "complex task → specialist model ${target.model}")
            }
            RequestComplexity.SIMPLE -> {
                Timber.tag("LlmRouter").d("Route=cloud/primary, model=${cloud.model}")
                RoutingDecision.Ready(withLocalFailover(cloud), "simple task → primary model ${cloud.model}")
            }
        }
    }

    private suspend fun withLocalFailover(primary: LlmProvider): LlmProvider =
        if (local.isAvailable()) FailoverLlmProvider(primary, local) else primary
}
