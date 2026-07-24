package com.hermes.agent.debug

import android.app.Activity
import android.app.Application
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugScreenAwakeTest {
    @Test
    fun `enabled hook keeps created activities awake`() {
        val application = RecordingApplication()
        DebugScreenAwake.install(application, enabled = true)
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()

        application.callbacks?.onActivityCreated(activity, null)

        assertNotNull(application.callbacks)
        assertEquals(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
    }

    @Test
    fun `disabled hook leaves application lifecycle unchanged`() {
        val application = RecordingApplication()

        DebugScreenAwake.install(application, enabled = false)

        assertEquals(null, application.callbacks)
    }

    private class RecordingApplication : Application() {
        var callbacks: Application.ActivityLifecycleCallbacks? = null

        override fun registerActivityLifecycleCallbacks(
            callback: Application.ActivityLifecycleCallbacks,
        ) {
            callbacks = callback
        }
    }
}