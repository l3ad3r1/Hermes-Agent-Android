package com.hermes.agent.debug

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import com.hermes.agent.BuildConfig

/** Keeps every Jeeves activity awake during debug sessions without changing system settings. */
object DebugScreenAwake {
    fun install(
        application: Application,
        enabled: Boolean = BuildConfig.DEBUG,
    ) {
        if (!enabled) return
        application.registerActivityLifecycleCallbacks(KeepScreenAwakeCallbacks)
    }

    private object KeepScreenAwakeCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            keepAwake(activity)
        }

        override fun onActivityResumed(activity: Activity) {
            keepAwake(activity)
        }

        private fun keepAwake(activity: Activity) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}