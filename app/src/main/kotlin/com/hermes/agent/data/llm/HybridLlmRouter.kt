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
    suspend fun route(
        messages: List<LlmMessage>,
        context: RoutingContext = RoutingContext(),
    ): RoutingDecision
}

@Singleton
class HybridLlmRouter @Inject constructor(
    private val cloud: CloudLlmProvider,
    @Named("cloudAux") private val specialised: CloudLlmProvider,
    private val local: LocalLlmProvider,
    private val settings: SettingsRepository,
    private val profileProviderFactory: ProfileCloudProviderFactory,
    private val routingPolicy: LlmRoutingPolicy,
) : LlmRouter {

    internal constructor(
        cloud: CloudLlmProvider,
        specialised: CloudLlmProvider,
        local: LocalLlmProvider,
        settings: SettingsRepository,
    ) : this(
        cloud,
        specialised,
        local,
        settings,
        object : ProfileCloudProviderFactory {
            override fun create(profile: com.hermes.agent.data.settings.CloudProviderProfile): CloudLlmProvider =
                error("No profile provider factory configured in this test.")
        },
        QualityAwareLlmRoutingPolicy(),
    )

    override suspend fun route(
        messages: List<LlmMessage>,
        context: RoutingContext,
    ): RoutingDecision {
        val s = settings.current()
        val localAvailable = available(local)
        val cloudCandidates = buildList {
            val primaryRepresentedInRegistry = s.cloudProviderProfiles.any {
                it.enabled && it.apiKey.isNotBlank() &&
                    it.baseUrl.trimEnd('/') == s.cloudBaseUrl.trimEnd('/') &&
                    it.model == s.cloudModel
            }
            if (s.cloudEnabled && !primaryRepresentedInRegistry && available(cloud)) {
                add(
                    LlmRouteCandidate(
                        provider = cloud,
                        tier = LlmModelTier.PRIMARY_CLOUD,
                        quality = 0.78,
                        cost = 0.35,
                        latency = 0.80,
                        toolReliability = 0.90,
                    ),
                )
            }
            val specialistBaseUrl = s.auxBaseUrl.ifBlank { s.cloudBaseUrl }
            val specialistApiKey = s.auxApiKey.ifBlank { s.cloudApiKey }
            val specialistRepresentedInRegistry = s.cloudProviderProfiles.any {
                it.enabled && it.apiKey == specialistApiKey &&
                    it.baseUrl.trimEnd('/') == specialistBaseUrl.trimEnd('/') &&
                    it.model == s.auxModel
            }
            if (s.cloudEnabled && !specialistRepresentedInRegistry && available(specialised)) {
                add(
                    LlmRouteCandidate(
                        provider = specialised,
                        tier = LlmModelTier.SPECIALIST_CLOUD,
                        quality = 0.94,
                        cost = 0.85,
                        latency = 0.45,
                        toolReliability = 0.96,
                    ),
                )
            }
            if (s.cloudEnabled) {
                s.cloudProviderProfiles
                    .asSequence()
                    .filter { it.enabled && it.apiKey.isNotBlank() }
                    .forEach { profile ->
                        val provider = profileProviderFactory.create(profile)
                        if (available(provider)) {
                            add(
                                LlmRouteCandidate(
                                    provider = provider,
                                    tier = if (profile.quality >= 0.85) {
                                        LlmModelTier.SPECIALIST_CLOUD
                                    } else {
                                        LlmModelTier.PRIMARY_CLOUD
                                    },
                                    quality = profile.quality.coerceIn(0.0, 1.0),
                                    cost = profile.cost.coerceIn(0.0, 1.0),
                                    latency = profile.latency.coerceIn(0.0, 1.0),
                                    toolReliability = profile.toolReliability.coerceIn(0.0, 1.0),
                                ),
                            )
                        }
                    }
            }
        }

        if (cloudCandidates.isEmpty()) {
            if (localAvailable) {
                Timber.tag("LlmRouter").d("No cloud provider is available; using final local fallback")
                return RoutingDecision.Ready(local, "all cloud providers unavailable → local fallback")
            }
            val reason = if (!s.cloudEnabled) {
                "Cloud is disabled and local model is not downloaded. Configure a Cloud LLM or download the local model in Settings."
            } else {
                "Cloud is enabled but no API key is set, and local model is not downloaded. Add one in Settings."
            }
            return RoutingDecision.Unavailable(cloud, reason)
        }

        // LLMRouter ranks cloud models only. The local model is deliberately
        // excluded from quality/cost competition and appended as the final
        // offline fallback after every configured cloud provider.
        val ranked = routingPolicy.rank(messages, context, cloudCandidates)
        val selected = ranked.first()
        val candidate = selected.candidate
        val rankedProviders = ranked.map { it.candidate.provider }
        val remainingProviders = cloudCandidates
            .asSequence()
            .filter { fallback -> ranked.none { it.candidate.provider === fallback.provider } }
            .sortedByDescending { it.quality }
            .map { it.provider }
            .toList()
        val localFallback = if (localAvailable) listOf(local) else emptyList()
        val chain = (rankedProviders + remainingProviders + localFallback)
            .distinctBy { "${it.name}|${it.model}" }
        val target = if (chain.size == 1) chain.first() else RoutedProviderChain(chain)
        val requirement = if (selected.satisfiesRequirements) "quality gate met" else "best available"
        val reason = "%s → %s (%s, required quality %.2f)".format(
            candidate.tier.name.lowercase(),
            candidate.provider.model,
            requirement,
            selected.requiredQuality,
        )
        Timber.tag("LlmRouter").d("Route=%s, score=%.3f, %s", candidate.tier, selected.score, reason)
        return RoutingDecision.Ready(target, reason)
    }

    private suspend fun available(provider: LlmProvider): Boolean =
        runCatching { provider.isAvailable() }
            .onFailure { Timber.tag("LlmRouter").w(it, "Availability check failed for %s", provider.name) }
            .getOrDefault(false)

}
