package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Presence and ambient context snapshot entity.
 * Ported from OpenClaw presence & ambient state specification.
 */
@Entity(
    tableName = "presence_logs",
    indices = [
        Index(value = ["timestamp"]),
    ],
)
data class PresenceLogEntity(
    @PrimaryKey
    val id: String,
    val timestamp: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationName: String? = null,
    val batteryLevel: Int = 100,
    val isCharging: Boolean = false,
    val networkType: String = "UNKNOWN",
    val activity: String = "UNKNOWN",
    val screenOn: Boolean = false,
    val contextSummary: String = "",
)
