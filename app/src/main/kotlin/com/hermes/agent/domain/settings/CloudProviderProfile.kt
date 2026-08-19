package com.hermes.agent.domain.settings

import kotlinx.serialization.Serializable

/** One OpenAI-compatible provider available to the model router. */
@Serializable
data class CloudProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val modelAutoSelected: Boolean = true,
    val apiKey: String,
    val enabled: Boolean = true,
    val quality: Double,
    val cost: Double,
    val latency: Double,
    val toolReliability: Double,
)
