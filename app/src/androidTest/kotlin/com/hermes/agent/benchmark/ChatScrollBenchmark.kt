package com.hermes.agent.benchmark

import android.content.Intent
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.agent.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scroll and UI responsiveness benchmark.
 */
@RunWith(AndroidJUnit4::class)
class ChatScrollBenchmark {

    @Test
    fun testChatListScrollingResponsiveness() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()

        assertNotNull(activity)

        // Inject smooth scroll gestures
        val downTime = android.os.SystemClock.uptimeMillis()
        val eventDown = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 500f, 1500f, 0)
        val eventMove = MotionEvent.obtain(downTime, downTime + 100, MotionEvent.ACTION_MOVE, 500f, 500f, 0)
        val eventUp = MotionEvent.obtain(downTime, downTime + 200, MotionEvent.ACTION_UP, 500f, 500f, 0)

        instrumentation.sendPointerSync(eventDown)
        instrumentation.sendPointerSync(eventMove)
        instrumentation.sendPointerSync(eventUp)

        instrumentation.waitForIdleSync()
        activity?.finish()
    }
}
