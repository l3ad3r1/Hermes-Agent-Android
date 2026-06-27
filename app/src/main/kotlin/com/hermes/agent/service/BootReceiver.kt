package com.hermes.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Restarts the always-on agent service after a device reboot so Hermes resumes
 * 24/7 operation without the user re-opening the app. Registered for
 * RECEIVE_BOOT_COMPLETED in the manifest.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            Timber.i("Boot completed — restarting Hermes agent service")
            AgentServiceController.start(context)
        }
    }
}
