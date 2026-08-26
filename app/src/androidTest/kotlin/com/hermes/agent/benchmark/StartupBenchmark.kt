package com.hermes.agent.benchmark

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.agent.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup benchmark measuring cold and warm launch latency.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @Test
    fun testColdStartupTiming() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        val startTime = SystemClock.elapsedRealtime()

        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val activity = instrumentation.startActivitySync(intent)

        val startupDurationMs = SystemClock.elapsedRealtime() - startTime

        instrumentation.waitForIdleSync()
        assertTrue("Startup took longer than 5000ms: ${startupDurationMs}ms", startupDurationMs < 5000)

        activity?.finish()
    }
}
