package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.hermes.agent.domain.model.Connector
import com.hermes.agent.domain.model.ConnectorType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "connectors")
data class ConnectorEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val configJson: String,
    val isEnabled: Boolean,
    val createdAt: Long,
    val lastUsedAt: Long?,
) {
    fun toDomain(): Connector = Connector(
        id = id,
        name = name,
        type = ConnectorType.valueOf(type),
        config = Json.decodeFromString(configJson),
        isEnabled = isEnabled,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
    )

    companion object {
        fun fromDomain(c: Connector) = ConnectorEntity(
            id = c.id,
            name = c.name,
            type = c.type.name,
            configJson = Json.encodeToString(c.config),
            isEnabled = c.isEnabled,
            createdAt = c.createdAt,
            lastUsedAt = c.lastUsedAt,
        )
    }
}
