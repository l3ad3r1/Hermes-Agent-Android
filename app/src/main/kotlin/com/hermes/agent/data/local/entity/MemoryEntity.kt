package com.hermes.agent.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted long-term memory row. Maps 1:1 to [com.hermes.agent.domain.model.Memory].
 *
 * The `embedding` column is a BLOB that stores a flat float array once the
 * on-device embedding model is wired in Phase 2. It is nullable for Phase 1
 * so we can store text-only memories immediately.
 *
 * A separate `sqlite_vss` virtual table (created in [com.hermes.agent.data.local.HermesDatabase]
 * via a migration in Phase 2) will index this column for ANN search.
 */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "embedding")
    val embedding: ByteArray? = null,

    @ColumnInfo(name = "relevance_score")
    val relevanceScore: Float = 0f,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "last_accessed_at")
    val lastAccessedAt: Long,

    @ColumnInfo(name = "access_count")
    val accessCount: Int = 0,
) {
    // Manual equals/hashCode because ByteArray is in the data class.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryEntity) return false
        return id == other.id &&
            content == other.content &&
            relevanceScore == other.relevanceScore &&
            createdAt == other.createdAt &&
            lastAccessedAt == other.lastAccessedAt &&
            accessCount == other.accessCount &&
            ((embedding == null && other.embedding == null) ||
                (embedding != null && other.embedding != null && embedding.contentEquals(other.embedding)))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + relevanceScore.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + lastAccessedAt.hashCode()
        result = 31 * result + accessCount
        return result
    }
}
