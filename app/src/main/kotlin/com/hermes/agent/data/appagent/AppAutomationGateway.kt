package com.hermes.agent.data.appagent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Process-local boundary between Hermes tools and Android UI automation.
 *
 * Production uses the user-enabled AccessibilityService. Instrumented tests
 * provide a UiAutomation-backed implementation, so tool behavior can be
 * verified on a device without depending on AccessibilityService rebinding.
 */
interface AppAutomationGateway {
    fun activeWindowRoot(): AccessibilityNodeInfo?

    fun screenBounds(): Rect?

    fun dispatchTap(x: Float, y: Float): Boolean

    fun dispatchSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 400L,
    ): Boolean

    fun setText(node: AccessibilityNodeInfo, text: CharSequence): Boolean
}
