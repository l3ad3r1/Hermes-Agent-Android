package com.hermes.agent.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted RAG chunk row. Maps 1:1 to [com.hermes.agent.domain.rag.Chunk].
 *
 * The `embedding` column is a BLOB that stores a flat float array. Phase 2
 * writes null here (the in-memory [com.hermes.agent.data.memory.VectorStore]
 * is the source of truth). Phase 3 will populate this column and create a
 * `sqlite_vss` virtual table on it for persistent ANN search.
 */
@Entity(
    tableName = "document_chunks",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("document_id"),
        Index(value = ["document_id", "ordinal"]),
    ]
)
data class DocumentChunkEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "document_id")
    val documentId: String,

    @ColumnInfo(name = "ordinal")
    val ordinal: Int,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "embedding")
    val embedding: ByteArray? = null,

    @ColumnInfo(name = "token_count")
    val tokenCount: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DocumentChunkEntity) return false
        return id == other.id &&
            documentId == other.documentId &&
            ordinal == other.ordinal &&
            text == other.text &&
            tokenCount == other.tokenCount &&
            ((embedding == null && other.embedding == null) ||
                (embedding != null && other.embedding != null && embedding.contentEquals(other.embedding)))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + documentId.hashCode()
        result = 31 * result + ordinal
        result = 31 * result + text.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + tokenCount
        return result
    }
}
