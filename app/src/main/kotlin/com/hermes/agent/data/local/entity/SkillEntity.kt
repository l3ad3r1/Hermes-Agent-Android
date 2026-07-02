package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.hermes.agent.domain.model.Skill
import com.hermes.agent.domain.model.SkillLifecycle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val version: String,
    val content: String,
    val category: String,
    val tagsJson: String,
    val isBuiltIn: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    // Conditional activation (v0.7.23): requires/fallback tool lists.
    val requiresToolsJson: String = "[]",
    val fallbackForToolsJson: String = "[]",
    // Curator lifecycle (v0.7.23).
    val lifecycleState: String = SkillLifecycle.ACTIVE.name,
    val pinned: Boolean = false,
    val useCount: Int = 0,
    val lastUsedAt: Long? = null,
) {
    fun toDomain() = Skill(
        id = id,
        name = name,
        description = description,
        version = version,
        content = content,
        category = category,
        tags = decodeList(tagsJson),
        isBuiltIn = isBuiltIn,
        createdAt = createdAt,
        updatedAt = updatedAt,
        requiresTools = decodeList(requiresToolsJson),
        fallbackForTools = decodeList(fallbackForToolsJson),
        lifecycleState = runCatching { SkillLifecycle.valueOf(lifecycleState) }
            .getOrDefault(SkillLifecycle.ACTIVE),
        pinned = pinned,
        useCount = useCount,
        lastUsedAt = lastUsedAt,
    )

    companion object {
        private fun decodeList(json: String): List<String> =
            runCatching { Json.decodeFromString<List<String>>(json) }.getOrDefault(emptyList())

        fun fromDomain(s: Skill) = SkillEntity(
            id = s.id,
            name = s.name,
            description = s.description,
            version = s.version,
            content = s.content,
            category = s.category,
            tagsJson = Json.encodeToString(s.tags),
            isBuiltIn = s.isBuiltIn,
            createdAt = s.createdAt,
            updatedAt = s.updatedAt,
            requiresToolsJson = Json.encodeToString(s.requiresTools),
            fallbackForToolsJson = Json.encodeToString(s.fallbackForTools),
            lifecycleState = s.lifecycleState.name,
            pinned = s.pinned,
            useCount = s.useCount,
            lastUsedAt = s.lastUsedAt,
        )
    }
}
