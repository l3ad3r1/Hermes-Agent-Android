package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.LogLevel
import com.hermes.agent.domain.plugin.PluginContext
import com.hermes.agent.data.settings.SettingsRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [PluginContext] implementation given to every loaded plugin.
 *
 * Provides plugins with controlled access to host services without
 * exposing the Android [android.content.Context] directly — that would
 * let them escape the sandbox (file system, shared prefs, system
 * services, etc.).
 */
@Singleton
class HostPluginContext @Inject constructor(
    private val settings: SettingsRepository,
) : PluginContext {

    override fun log(tag: String, level: LogLevel, message: String, throwable: Throwable?) {
        val t = Timber.tag("Plugin:$tag")
        when (level) {
            LogLevel.VERBOSE -> t.v(message, throwable)
            LogLevel.DEBUG -> t.d(message, throwable)
            LogLevel.INFO -> t.i(message, throwable)
            LogLevel.WARN -> t.w(throwable, message)
            LogLevel.ERROR -> t.e(throwable, message)
        }
    }

    override suspend fun hostSetting(key: String): String? = when (key) {
        "on_device_enabled" -> settings.current().onDeviceEnabled.toString()
        "cloud_enabled" -> settings.current().cloudEnabled.toString()
        "cloud_model" -> settings.current().cloudModel
        "on_device_model" -> settings.current().onDeviceModel
        else -> null
    }

    override fun hostAppVersion(): Int = com.hermes.agent.BuildConfig.VERSION_CODE
}
