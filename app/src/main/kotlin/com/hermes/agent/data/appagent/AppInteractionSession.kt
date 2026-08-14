package com.hermes.agent.data.appagent

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/** Grants short-lived UI-control authority to the app approved through app_launch. */
@Singleton
class AppInteractionSession @Inject constructor() {
    private var authorizedPackage: String? = null
    private var expiresAtElapsedMs: Long = 0L

    @Synchronized
    fun authorize(packageName: String) {
        authorizedPackage = packageName
        expiresAtElapsedMs = SystemClock.elapsedRealtime() + SESSION_TTL_MS
    }

    @Synchronized
    fun clear() {
        authorizedPackage = null
        expiresAtElapsedMs = 0L
    }

    @Synchronized
    fun rejectionReason(visiblePackage: String): String? {
        val expected = authorizedPackage
            ?: return "Launch and approve the target app with app_launch before analyzing it."
        if (SystemClock.elapsedRealtime() > expiresAtElapsedMs) {
            authorizedPackage = null
            return "The approved app-control session expired. Launch the target app again."
        }
        if (visiblePackage.isEmpty() || visiblePackage != expected) {
            return "The visible app ($visiblePackage) is not the approved app ($expected). " +
                "Launch the target app again before interacting."
        }
        return null
    }

    private companion object {
        const val SESSION_TTL_MS = 10 * 60 * 1000L
    }
}
