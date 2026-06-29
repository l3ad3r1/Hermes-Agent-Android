package com.hermes.agent.data.security

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Samsung Knox integration surface — Phase 1 scaffold.
 *
 * What this is:
 *   - Declares the API surface the rest of the app will call once the
 *     Samsung Knox SDK is added as a gradle dependency in Phase 3.
 *   - Reports Knox availability as `false` in Phase 1 (no SDK wired) so
 *     the Settings UI can show a "not available on this device" state
 *     gracefully.
 *
 * What this is NOT:
 *   - It does NOT call any Samsung SDK. The Knox SDK requires a Samsung
 *     device and a Knox Platform for Enterprise (KPE) entitlement
 *     check; both are deferred to Phase 3.
 *
 * See Section 4.2 and Section 5 of the plan for the production design.
 */
@Singleton
class KnoxSecurityManager @Inject constructor() {

    /**
     * True iff the Samsung Knox SDK is loaded and the device is Knox-capable.
     *
     * Phase 1 always returns false. Phase 3 will probe for the Knox SDK at
     * runtime via reflection (`Class.forName("com.samsung.android.knox.EnterpriseDeviceManager")`)
     * and gate the result on a KPE entitlement check.
     */
    // Computed once (and logged once) per process — this getter is polled by the
    // UI, and re-probing + logging on every access spammed the log buffer.
    val isKnoxAvailable: Boolean by lazy {
        runCatching {
            Class.forName("com.samsung.android.knox.EnterpriseDeviceManager")
            Timber.tag("Knox").d("Knox SDK class detected.")
            // A real check would query the SDK here. For Phase 1, treat detection as availability.
            false
        }.getOrElse {
            Timber.tag("Knox").d("Knox SDK not present; running in compatibility mode.")
            false
        }
    }

    /**
     * Whether the agent process is currently running inside a hardened
     * Knox container. Phase 3 will query the Knox container SDK.
     */
    suspend fun isInsideKnoxContainer(): Boolean = false

    /**
     * Mark the agent's local database file as Knox-encrypted-at-rest.
     * Phase 3 will call into the Knox Storage SDK to enforce this.
     */
    suspend fun enableDatabaseEncryption(): Boolean {
        Timber.tag("Knox").i("enableDatabaseEncryption() called (no-op in Phase 1)")
        return false
    }

    /**
     * Register the agent for Knox Platform for Enterprise (KPE) license
     * validation. Phase 3 will call the real KPE license API.
     */
    suspend fun activateKpeLicense(licenseKey: String): Boolean {
        Timber.tag("Knox").i("activateKpeLicense() called (no-op in Phase 1)")
        return false
    }
}
