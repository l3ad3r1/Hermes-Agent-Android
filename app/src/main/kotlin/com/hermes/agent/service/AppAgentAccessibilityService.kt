package com.hermes.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import timber.log.Timber
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Service that provides AppAgent capabilities (UI inspection, gestures, screenshots).
 * Registered in AndroidManifest.xml and enabled by the user in Settings -> Accessibility.
 */
class AppAgentAccessibilityService : AccessibilityService() {

    private val executor: Executor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.i("AppAgentAccessibilityService connected")
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We do not actively listen to events for now. We only query the active window on demand.
    }

    override fun onInterrupt() {
        Timber.w("AppAgentAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Timber.i("AppAgentAccessibilityService destroyed")
    }

    fun getActiveWindowRoot(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }

    fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 400L): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Takes a screenshot using the AccessibilityService API (requires API 30+).
     */
    suspend fun captureScreen(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Timber.e("Screenshots via AccessibilityService require Android 11+ (API 30+)")
            return null
        }
        
        val deferred = CompletableDeferred<Bitmap?>()
        
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                        screenshotResult.hardwareBuffer,
                        screenshotResult.colorSpace
                    )
                    // Copy hardware bitmap to software bitmap so it can be drawn on and saved
                    val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBitmap?.recycle()
                    screenshotResult.hardwareBuffer.close()
                    
                    deferred.complete(softwareBitmap)
                }

                override fun onFailure(errorCode: Int) {
                    Timber.e("Screenshot failed with error code: $errorCode")
                    deferred.complete(null)
                }
            }
        )
        return deferred.await()
    }

    companion object {
        var instance: AppAgentAccessibilityService? = null
            private set
            
        val isAvailable: Boolean
            get() = instance != null
    }
}
