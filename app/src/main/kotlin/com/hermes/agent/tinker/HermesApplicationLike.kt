package com.hermes.agent.tinker

import android.app.Application
import android.content.Context
import android.content.Intent
import com.hermes.agent.HermesAppStartup
import com.hermes.agent.tinker.loader.HermesTinkerApplication
import com.tencent.tinker.entry.DefaultApplicationLike
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals
import dagger.hilt.android.internal.managers.ApplicationComponentManager
import dagger.hilt.android.internal.managers.ComponentSupplier

/**
 * Where Hermes actually starts. Tinker's shell ([HermesTinkerApplication]) creates this through
 * the (possibly patched) class loader after installing any patch, so this class and everything it
 * reaches — the Hilt graph included — can be hot-fixed.
 *
 * Constructed reflectively by Tinker with exactly this signature; kept by proguard-rules.pro.
 */
class HermesApplicationLike(
    application: Application,
    tinkerFlags: Int,
    tinkerLoadVerifyFlag: Boolean,
    applicationStartElapsedTime: Long,
    applicationStartMillisTime: Long,
    tinkerResultIntent: Intent,
) : DefaultApplicationLike(
    application,
    tinkerFlags,
    tinkerLoadVerifyFlag,
    applicationStartElapsedTime,
    applicationStartMillisTime,
    tinkerResultIntent,
) {

    override fun onBaseContextAttached(base: Context) {
        super.onBaseContextAttached(base)
        val app = application
        // Content providers (App Startup's MemoryMonitorInitializer) ask for the Hilt component
        // before Application.onCreate, so the manager must exist now; the component itself is
        // built lazily on first use, exactly as Hilt's generated application would do it.
        (app as HermesTinkerApplication).setComponentManager(
            ApplicationComponentManager(ComponentSupplier { HermesComponentFactory.create(app) }),
        )
        HermesTinker.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Tinker's :patch process only prepares patches; it gets none of the app's start-up.
        if (ShareTinkerInternals.isInPatchProcess(application)) return
        HermesAppStartup.start(application)
    }
}
