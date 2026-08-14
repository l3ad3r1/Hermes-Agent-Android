package com.hermes.agent.data.appagent

import android.app.UiAutomation
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo

/** Device-test backend that does not require Hermes' AccessibilityService to bind. */
class UiAutomationAppAutomationGateway(
    private val uiAutomation: UiAutomation,
    private val context: Context,
) : AppAutomationGateway {

    override fun activeWindowRoot(): AccessibilityNodeInfo? = uiAutomation.rootInActiveWindow

    override fun screenBounds(): Rect? =
        context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds?.let(::Rect)

    override fun dispatchTap(x: Float, y: Float): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = motionEvent(downTime, MotionEvent.ACTION_DOWN, x, y)
        val downAccepted = uiAutomation.injectInputEvent(down, true)
        down.recycle()

        val up = motionEvent(downTime, MotionEvent.ACTION_UP, x, y)
        val upAccepted = uiAutomation.injectInputEvent(up, true)
        up.recycle()
        return downAccepted && upAccepted
    }

    override fun dispatchSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ): Boolean {
        val downTime = SystemClock.uptimeMillis()
        var accepted = inject(downTime, MotionEvent.ACTION_DOWN, startX, startY)
        val steps = 10
        repeat(steps) { index ->
            SystemClock.sleep((durationMs / steps).coerceAtLeast(1L))
            val fraction = (index + 1f) / steps
            accepted = inject(
                downTime,
                MotionEvent.ACTION_MOVE,
                startX + (endX - startX) * fraction,
                startY + (endY - startY) * fraction,
            ) && accepted
        }
        return inject(downTime, MotionEvent.ACTION_UP, endX, endY) && accepted
    }

    override fun setText(node: AccessibilityNodeInfo, text: CharSequence): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun inject(
        downTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): Boolean {
        val event = motionEvent(downTime, action, x, y)
        return try {
            uiAutomation.injectInputEvent(event, true)
        } finally {
            event.recycle()
        }
    }

    private fun motionEvent(
        downTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent = MotionEvent.obtain(
        downTime,
        SystemClock.uptimeMillis(),
        action,
        x,
        y,
        0,
    ).apply {
        source = InputDevice.SOURCE_TOUCHSCREEN
    }
}
