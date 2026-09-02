package com.hermes.agent.data.presence

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.hermes.agent.data.local.dao.PresenceLogDao
import com.hermes.agent.data.local.entity.PresenceLogEntity
import com.hermes.agent.domain.model.PresencePlace
import com.hermes.agent.domain.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages ambient signals and on-device presence context.
 * Ported from OpenClaw presence specification (`docs/nodes/presence.md`).
 *
 * **Privacy invariant.** A location fix is read only to resolve which of the
 * user's own labelled [PresencePlace]s they are in. The fix itself is discarded
 * immediately: `presence_logs` stores the resolved label and nothing else, and
 * no coordinate is ever logged, persisted, or put in a prompt. Motion is derived
 * from how far the fix moved since the previous snapshot rather than from an
 * activity-recognition service, which keeps this dependency-free.
 */
@Singleton
class PresenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val presenceLogDao: PresenceLogDao,
    private val settingsRepository: SettingsRepository,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Last fix, kept in memory only, purely to derive a motion estimate.
     * Never written to disk; cleared with the process.
     */
    private var lastFix: Triple<Double, Double, Long>? = null

    suspend fun captureSnapshot(): PresenceLogEntity = withContext(Dispatchers.IO) {
        val (batteryLevel, isCharging) = getBatteryStatus()
        val networkType = getNetworkType()
        val screenOn = isScreenOn()
        val (place, motion) = resolvePlaceAndMotion()

        val summary = buildString {
            append("Battery: $batteryLevel%${if (isCharging) " (Charging)" else ""}")
            append(" | Network: $networkType")
            append(" | Screen: ${if (screenOn) "On" else "Off"}")
            if (place != null) append(" | Place: $place")
            append(" | Motion: $motion")
        }

        val entity = PresenceLogEntity(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            locationName = place,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            networkType = networkType,
            activity = motion,
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

    /** The user's labelled places. Coordinates stay here and in settings only. */
    suspend fun places(): List<PresencePlace> = runCatching {
        PresencePlace.normalize(
            json.decodeFromString(
                ListSerializer(PresencePlace.serializer()),
                settingsRepository.current().presencePlacesJson,
            ),
        )
    }.getOrDefault(emptyList())

    suspend fun savePlaces(places: List<PresencePlace>) {
        val normalized = PresencePlace.normalize(places)
        settingsRepository.setPresencePlacesJson(
            json.encodeToString(ListSerializer(PresencePlace.serializer()), normalized),
        )
    }

    /** A fresh fix, for the settings screen's "use my current location" action. */
    suspend fun currentFix(): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        readLastKnownLocation()?.let { it.latitude to it.longitude }
    }

    /**
     * @return the resolved place label (null when outside every place or location
     *   is unavailable) and a coarse motion estimate.
     */
    private suspend fun resolvePlaceAndMotion(): Pair<String?, String> {
        val fix = readLastKnownLocation() ?: return null to "unknown"
        val now = System.currentTimeMillis()

        val motion = lastFix?.let { (prevLat, prevLon, prevTime) ->
            val seconds = ((now - prevTime) / 1000.0).coerceAtLeast(1.0)
            val metres = PresencePlace.distanceMeters(prevLat, prevLon, fix.latitude, fix.longitude)
            val metresPerSecond = metres / seconds
            when {
                metres < 40 -> "still"
                metresPerSecond < 2.0 -> "walking"
                else -> "driving"
            }
        } ?: "unknown"

        lastFix = Triple(fix.latitude, fix.longitude, now)

        val label = PresencePlace.resolveLabel(places(), fix.latitude, fix.longitude)
        // Deliberately no coordinate in this log line.
        Timber.tag("PresenceManager").d("Presence resolved: place=%s motion=%s", label ?: "out", motion)
        return (label ?: "out") to motion
    }

    /**
     * Last known fix from whichever provider has one. Uses [LocationManager]
     * rather than Play Services so presence adds no dependency; a null result
     * (no permission, no provider, no cached fix) simply means "unknown".
     */
    private fun readLastKnownLocation(): Location? {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            lm.getProviders(true)
                .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (se: SecurityException) {
            null
        } catch (e: Exception) {
            Timber.tag("PresenceManager").w(e, "Location read failed")
            null
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
