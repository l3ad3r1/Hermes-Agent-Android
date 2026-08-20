package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.LogLevel
import com.hermes.agent.domain.plugin.PluginContext
import com.hermes.agent.domain.settings.SettingsRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Product-owned context that exposes a narrow set of Hermes host services. */
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
        "cloud_enabled" -> settings.current().cloudEnabled.toString()
        "cloud_model" -> settings.current().cloudModel
        else -> null
    }

    override fun hostAppVersion(): Int = com.hermes.agent.BuildConfig.VERSION_CODE
}
