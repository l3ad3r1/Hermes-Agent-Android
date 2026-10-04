package com.hermes.agent.tinker

import android.app.Application
import dagger.hilt.android.internal.modules.ApplicationContextModule

/**
 * Builds the Hilt singleton component for the Tinker shell, which cannot be the
 * `@HiltAndroidApp` class itself (see HermesTinkerApplication).
 *
 * This is the same call Hilt's generated `Hilt_HermesApp` makes. It has to go through reflection
 * because Hilt's Gradle plugin generates the root component in a separate step after the app's
 * own sources compile. The names are Hilt's stable generated names for the `@HiltAndroidApp`
 * root [com.hermes.agent.HermesApp]; proguard-rules.pro keeps them and
 * HermesComponentFactoryTest checks they exist.
 */
internal object HermesComponentFactory {
    const val COMPONENT = "com.hermes.agent.DaggerHermesApp_HiltComponents_SingletonC"

    fun create(app: Application): Any {
        // The defining loader of this class: the patched one when a patch is loaded, so the whole
        // graph is created from patched classes.
        val component = Class.forName(COMPONENT, true, HermesComponentFactory::class.java.classLoader)
        val builder = component.getMethod("builder").invoke(null)!!
        builder.javaClass.getMethod("applicationContextModule", ApplicationContextModule::class.java)
            .invoke(builder, ApplicationContextModule(app))
        return builder.javaClass.getMethod("build").invoke(builder)!!
    }
}
