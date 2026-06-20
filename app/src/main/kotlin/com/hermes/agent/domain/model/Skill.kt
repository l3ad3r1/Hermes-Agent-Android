package com.hermes.agent.domain.model

data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val version: String = "1.0.0",
    val content: String,
    val category: String = "general",
    val tags: List<String> = emptyList(),
    val isBuiltIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
