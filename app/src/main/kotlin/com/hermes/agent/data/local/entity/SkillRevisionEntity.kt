package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.hermes.agent.domain.model.SkillRevision

/**
 * Archived prior version of a skill. Rows are written by
 * `SkillRepositoryImpl.upsert` before it overwrites an existing skill, and
 * pruned to the newest few per skill so history cannot grow without bound.
 *
 * Deliberately not a foreign key on `skills`: a revision stays readable and
 * restorable even if the live skill row is deleted, and the repository clears
 * a skill's history explicitly when the skill itself is removed.
 */
@Entity(
    tableName = "skill_revisions",
    indices = [Index(value = ["skillId", "replacedAt"])],
)
data class SkillRevisionEntity(
    @PrimaryKey val id: String,
    val skillId: String,
    val skillName: String,
    val version: String,
    val description: String,
    val content: String,
    val note: String,
    val replacedAt: Long,
) {
    fun toDomain() = SkillRevision(
        id = id,
        skillId = skillId,
        skillName = skillName,
        version = version,
        description = description,
        content = content,
        note = note,
        replacedAt = replacedAt,
    )
}
