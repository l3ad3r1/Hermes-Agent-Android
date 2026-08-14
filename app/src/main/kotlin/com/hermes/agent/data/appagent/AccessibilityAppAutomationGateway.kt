package com.hermes.agent.data.appagent

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.hermes.agent.service.AppAgentAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** AccessibilityService-backed gateway used by the installed application. */
@Singleton
class AccessibilityAppAutomationGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppAutomationGateway {

    override fun activeWindowRoot(): AccessibilityNodeInfo? =
        AppAgentAccessibilityService.instance?.getActiveWindowRoot()

    override fun screenBounds(): Rect? {
        val windowManager = context.getSystemService(WindowManager::class.java) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect(windowManager.maximumWindowMetrics.bounds)
        } else {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also {
                windowManager.defaultDisplay.getRealMetrics(it)
            }
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    override fun dispatchTap(x: Float, y: Float): Boolean =
        AppAgentAccessibilityService.instance?.dispatchTap(x, y) == true

    override fun dispatchSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ): Boolean = AppAgentAccessibilityService.instance?.dispatchSwipe(
        startX,
        startY,
        endX,
        endY,
        durationMs,
    ) == true

    override fun setText(node: AccessibilityNodeInfo, text: CharSequence): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }
}
