package com.hermes.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hermes.agent.domain.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Restarts the [WakeWordService] on device boot if wake word and restartOnBoot are both enabled.
 */
@AndroidEntryPoint
class WakeWordBootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch {
            try {
                val settings = settingsRepository.current()
                if (settings.wakeWordEnabled && settings.wakeWordRestartOnBoot) {
                    Timber.tag("WakeWord").i("Boot completed — restarting WakeWordService as configured")
                    WakeWordService.startService(context)
                }
            } catch (t: Throwable) {
                Timber.tag("WakeWord").e(t, "Failed to check wake-word boot configuration")
            }
        }
    }
}
