package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import com.hermes.agent.domain.settings.CloudProviderProfile

/** Non-secret provider metadata, mirroring Hermes Desktop's built-in provider catalog. */
data class CloudProviderDefinition(
    val id: String,
    val name: String,
    val description: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val quality: Double,
    val cost: Double,
    val latency: Double,
    val toolReliability: Double,
)

object CloudProviderRegistry {
    val providers: List<CloudProviderDefinition> = listOf(
        CloudProviderDefinition("nvidia", "NVIDIA NIM", "NVIDIA-hosted and self-hosted NIM models", "https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct", 0.88, 0.15, 0.72, 0.90),
        CloudProviderDefinition("openrouter", "OpenRouter", "Aggregator for frontier and open models", "https://openrouter.ai/api/v1", "openai/gpt-4.1-mini", 0.94, 0.05, 0.55, 0.94),
        CloudProviderDefinition("llm7", "LLM7.io", "OpenAI-compatible multi-model gateway", "https://api.llm7.io/v1", "gpt-4.1-nano", 0.90, 0.05, 0.55, 0.88),
        CloudProviderDefinition("groq", "Groq", "Low-latency hosted open models", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", 0.88, 0.10, 0.98, 0.94),
        CloudProviderDefinition("mistral", "Mistral AI", "Mistral's direct model API", "https://api.mistral.ai/v1", "mistral-large-latest", 0.70, 0.20, 0.82, 0.90),
        CloudProviderDefinition("kilo", "Kilo Code", "Coding-model gateway", "https://api.kilo.ai/api/openrouter", "openai/gpt-4.1-mini", 0.94, 0.05, 0.62, 0.94),
        CloudProviderDefinition("opencode", "OpenCode Zen", "Curated pay-as-you-go models", "https://opencode.ai/zen/v1", "big-pickle", 0.80, 0.05, 0.68, 0.88),
        CloudProviderDefinition("huggingface", "Hugging Face", "Inference Providers router", "https://router.huggingface.co/v1", "openai/gpt-oss-120b", 0.68, 0.05, 0.58, 0.82),
        CloudProviderDefinition("deepseek", "DeepSeek", "Direct DeepSeek chat and reasoning models", "https://api.deepseek.com/v1", "deepseek-chat", 0.91, 0.20, 0.62, 0.90),
    )

    fun definition(id: String): CloudProviderDefinition? = providers.firstOrNull { it.id == id }

    /** Order a provider's live catalog with the saved/curated choice first, then capable chat models. */
    fun orderModels(
        definition: CloudProviderDefinition,
        available: List<String>,
        savedModel: String,
    ): List<String> = available.distinct().filter { modelQualityScore(it) > -1_000 }.sortedWith(
        compareByDescending<String> { model ->
            when (model) {
                savedModel -> 10_000
                definition.defaultModel -> 9_000
                else -> modelQualityScore(model)
            }
        }.thenBy { it.lowercase() },
    )

    fun bestModel(
        definition: CloudProviderDefinition,
        available: List<String>,
    ): String? = compatibleModels(available).sortedWith(
        compareByDescending<String> { model ->
            if (model == definition.defaultModel) 9_000 else modelQualityScore(model)
        }.thenBy { it.lowercase() },
    ).firstOrNull()

    private fun compatibleModels(available: List<String>): List<String> =
        available.distinct().filter { modelQualityScore(it) > -1_000 }

    private fun modelQualityScore(model: String): Int {
        val id = model.lowercase()
        if (listOf("embed", "rerank", "whisper", "tts", "speech", "moderation", "image").any(id::contains)) {
            return -1_000
        }
        var score = 0
        if ("opus" in id) score += 180
        if ("pro" in id) score += 150
        if ("large" in id) score += 130
        if ("reason" in id || Regex("(^|[/_-])r1([/_-]|$)").containsMatchIn(id)) score += 120
        if ("instruct" in id || "chat" in id) score += 40
        if ("latest" in id) score += 30
        if ("preview" in id) score -= 10
        if ("free" in id) score -= 5
        val billions = Regex("""(?:^|[^0-9])(\d{1,3})b(?:[^0-9]|$)""")
            .findAll(id)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: 0
        return score + billions.coerceAtMost(500)
    }

    fun profile(definition: CloudProviderDefinition, apiKey: String = "") = CloudProviderProfile(
        id = definition.id,
        name = definition.name,
        baseUrl = definition.defaultBaseUrl,
        model = definition.defaultModel,
        apiKey = apiKey,
        enabled = apiKey.isNotBlank(),
        quality = definition.quality,
        cost = definition.cost,
        latency = definition.latency,
        toolReliability = definition.toolReliability,
    )
}
