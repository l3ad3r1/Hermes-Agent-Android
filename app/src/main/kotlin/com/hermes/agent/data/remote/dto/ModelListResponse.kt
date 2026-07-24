package com.hermes.agent.data.remote.dto

import kotlinx.serialization.Serializable

/** Minimal OpenAI-compatible response from GET /models. */
@Serializable
data class ModelListResponse(
    val data: List<ModelInfo> = emptyList(),
)

@Serializable
data class ModelInfo(
    val id: String,
)