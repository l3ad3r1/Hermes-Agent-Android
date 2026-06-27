package com.hermes.agent.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted RAG document row. Maps 1:1 to [com.hermes.agent.domain.rag.Document].
 *
 * The full extracted text is stored so the chunker can re-run if the
 * chunk-size parameter changes. Phase 3 may drop this column once the
 * migration story is settled.
 */
@Entity(
    tableName = "documents",
    indices = [Index("created_at")]
)
data class DocumentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "source_uri")
    val sourceUri: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "chunk_count")
    val chunkCount: Int = 0,
)
