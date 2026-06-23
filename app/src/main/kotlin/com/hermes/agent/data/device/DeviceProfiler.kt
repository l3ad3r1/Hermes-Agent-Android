package com.hermes.agent.data.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Structured snapshot of the device's hardware/OS capabilities. */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val soc: String,
    val abi: String,
    val cpuCores: Int,
    val totalRamGb: Double,
    val totalStorageGb: Double,
    val freeStorageGb: Double,
    val screen: String,
    val gpuRenderer: String,
    val gpuVendor: String,
    val glVersion: String,
    val batteryPct: Int,
    val sensors: List<String>,
) {
    /** Agent-readable summary committed to long-term memory. */
    fun toMemoryText(): String = buildString {
        append("[DEVICE PROFILE] The user's phone is a ")
        append("$manufacturer $model running Android $androidRelease (API $sdkInt). ")
        append("SoC: $soc; CPU: $cpuCores cores ($abi); RAM: ${fmt(totalRamGb)} GB; ")
        append("storage: ${fmt(freeStorageGb)} GB free of ${fmt(totalStorageGb)} GB. ")
        append("GPU: ${gpuRenderer.ifBlank { "unknown" }}")
        if (gpuVendor.isNotBlank()) append(" ($gpuVendor)")
        if (glVersion.isNotBlank()) append(", $glVersion")
        append(". Display: $screen. ")
        if (batteryPct in 0..100) append("Battery: $batteryPct%. ")
        if (sensors.isNotEmpty()) append("Sensors: ${sensors.joinToString(", ")}. ")
        append(
            "Use this to judge on-device capability — e.g. heavier local models need ample " +
                "RAM and a capable GPU; sensor-based features require the listed sensors.",
        )
    }

    private fun fmt(v: Double) = (v * 10).roundToInt() / 10.0
}

/** Reads the device's CPU/GPU/RAM/sensor/display/battery capabilities. */
@Singleton
class DeviceProfiler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun profile(): DeviceProfile = withContext(Dispatchers.Default) {
        val am = context.getSystemService(ActivityManager::class.java)
        val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val totalRamGb = memInfo.totalMem / GB

        val stat = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()
        val totalStorage = stat?.let { it.blockCountLong * it.blockSizeLong / GB } ?: 0.0
        val freeStorage = stat?.let { it.availableBlocksLong * it.blockSizeLong / GB } ?: 0.0

        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            Build.SOC_MANUFACTURER.isNotBlank()
        ) {
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim()
        } else {
            Build.HARDWARE
        }

        val (gpuRenderer, gpuVendor, glVersion) = queryGpu()
        val sensors = readSensors()
        val battery = readBatteryPct()
        val screen = readScreen()

        DeviceProfile(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            soc = soc.ifBlank { "unknown" },
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            cpuCores = Runtime.getRuntime().availableProcessors(),
            totalRamGb = totalRamGb,
            totalStorageGb = totalStorage,
            freeStorageGb = freeStorage,
            screen = screen,
            gpuRenderer = gpuRenderer,
            gpuVendor = gpuVendor,
            glVersion = glVersion,
            batteryPct = battery,
            sensors = sensors,
        )
    }

    private fun readSensors(): List<String> {
        val sm = context.getSystemService(SensorManager::class.java) ?: return emptyList()
        return runCatching {
            sm.getSensorList(Sensor.TYPE_ALL)
                .map { it.name }
                .distinct()
                .take(40)
        }.getOrDefault(emptyList())
    }

    private fun readBatteryPct(): Int = runCatching {
        val bm = context.getSystemService(BatteryManager::class.java)
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: run {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        }
    }.getOrDefault(-1)

    private fun readScreen(): String = runCatching {
        val dm = context.resources.displayMetrics
        val refresh = runCatching {
            val wm = context.getSystemService(WindowManager::class.java)
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.refreshRate ?: 0f
        }.getOrDefault(0f)
        val hz = if (refresh > 0) " @ ${refresh.roundToInt()}Hz" else ""
        "${dm.widthPixels}×${dm.heightPixels} (${dm.densityDpi} dpi)$hz"
    }.getOrDefault("unknown")

    /** Off-screen EGL/GLES context to read the GPU renderer/vendor strings. */
    private fun queryGpu(): Triple<String, String, String> {
        var display = EGL14.EGL_NO_DISPLAY
        var ctx = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE,
                ),
                0, configs, 0, 1, num, 0,
            )
            val cfg = configs[0] ?: return Triple("", "", "")
            ctx = EGL14.eglCreateContext(
                display, cfg, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            surface = EGL14.eglCreatePbufferSurface(
                display, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            EGL14.eglMakeCurrent(display, surface, surface, ctx)
            Triple(
                GLES20.glGetString(GLES20.GL_RENDERER).orEmpty(),
                GLES20.glGetString(GLES20.GL_VENDOR).orEmpty(),
                GLES20.glGetString(GLES20.GL_VERSION).orEmpty(),
            )
        } catch (t: Throwable) {
            Triple("", "", "")
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, ctx)
                EGL14.eglTerminate(display)
            }
        }
    }

    private companion object {
        const val GB = 1024.0 * 1024.0 * 1024.0
    }
}
