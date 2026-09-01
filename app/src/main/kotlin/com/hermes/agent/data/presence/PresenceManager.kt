package com.hermes.agent.data.presence

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import com.hermes.agent.data.local.dao.PresenceLogDao
import com.hermes.agent.data.local.entity.PresenceLogEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages ambient signals and on-device presence context.
 * Ported from OpenClaw presence and ambient node specification.
 */
@Singleton
class PresenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val presenceLogDao: PresenceLogDao,
) {

    suspend fun captureSnapshot(): PresenceLogEntity = withContext(Dispatchers.IO) {
        val (batteryLevel, isCharging) = getBatteryStatus()
        val networkType = getNetworkType()
        val screenOn = isScreenOn()

        val summary = buildString {
            append("Battery: $batteryLevel%${if (isCharging) " (Charging)" else ""}")
            append(" | Network: $networkType")
            append(" | Screen: ${if (screenOn) "On" else "Off"}")
        }

        val entity = PresenceLogEntity(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            networkType = networkType,
            screenOn = screenOn,
            contextSummary = summary,
        )

        try {
            presenceLogDao.insert(entity)
        } catch (e: Exception) {
            Timber.tag("PresenceManager").w(e, "Could not persist presence snapshot")
        }

        entity
    }

    suspend fun getLatestContextSummary(): String = withContext(Dispatchers.IO) {
        val latest = presenceLogDao.getLatest()
        if (latest != null && System.currentTimeMillis() - latest.timestamp < 15 * 60 * 1000L) {
            latest.contextSummary
        } else {
            captureSnapshot().contextSummary
        }
    }

    private fun getBatteryStatus(): Pair<Int, Boolean> {
        return try {
            val batteryStatus: Intent? = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else 100

            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            batteryPct to isCharging
        } catch (e: Exception) {
            100 to false
        }
    }

    private fun getNetworkType(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "UNKNOWN"
            val activeNetwork = cm.activeNetwork ?: return "NONE"
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return "UNKNOWN"

            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
                else -> "OTHER"
            }
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }

    private fun isScreenOn(): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isInteractive ?: false
        } catch (e: Exception) {
            false
        }
    }
}
