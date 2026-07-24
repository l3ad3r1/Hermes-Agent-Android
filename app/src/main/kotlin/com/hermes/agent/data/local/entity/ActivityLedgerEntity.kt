package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.hermes.agent.domain.model.ActivityEntry
import com.hermes.agent.domain.model.ActivityKind

/** Room row backing the "What Jeeves did" activity ledger. */
@Entity(
    tableName = "activity_ledger",
    indices = [Index(value = ["timestamp"])],
)
data class ActivityLedgerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val kindName: String,
    val origin: String,
    val conversationId: String?,
    val title: String,
    val detail: String,
    val success: Boolean,
) {
    fun toDomain() = ActivityEntry(
        id = id,
        timestamp = timestamp,
        kind = runCatching { ActivityKind.valueOf(kindName) }.getOrDefault(ActivityKind.TOOL_CALL),
        origin = origin,
        conversationId = conversationId,
        title = title,
        detail = detail,
        success = success,
    )

    companion object {
        fun fromDomain(entry: ActivityEntry) = ActivityLedgerEntity(
            id = entry.id,
            timestamp = entry.timestamp,
            kindName = entry.kind.name,
            origin = entry.origin,
            conversationId = entry.conversationId,
            title = entry.title,
            detail = entry.detail,
            success = entry.success,
        )
    }
}
