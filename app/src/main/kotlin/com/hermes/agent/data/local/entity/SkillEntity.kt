package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.hermes.agent.domain.model.Skill
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
) {
    fun toDomain() = Skill(
        id = id,
        name = name,
        description = description,
        version = version,
        content = content,
        category = category,
        tags = runCatching { Json.decodeFromString<List<String>>(tagsJson) }.getOrDefault(emptyList()),
        isBuiltIn = isBuiltIn,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
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
        )
    }
}
