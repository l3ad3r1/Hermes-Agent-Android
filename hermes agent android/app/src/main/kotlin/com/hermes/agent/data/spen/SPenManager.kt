package com.hermes.agent.data.spen

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Samsung S Pen SDK wrapper — Phase 3 scaffold.
 *
 * Per Section 5.3 of the plan: "Through the Samsung S Pen SDK, the
 * application receives real-time stroke data including pressure
 * sensitivity, tilt angle, and velocity, enabling the agent to
 * interpret handwritten notes, diagrams, and annotations as natural
 * input."
 *
 * What this is:
 *   - Honors the [SPenManager] contract so the UI layer can be built
 *     and tested against any S Pen-capable device.
 *   - Probes for the Samsung S Pen SDK at runtime via reflection. On
 *     S24 Ultra (and other Samsung Note-series devices) the SDK class
 *     `com.samsung.android.sdk.pen.Spen` is present and [isAvailable]
 *     returns true. On non-Samsung devices it returns false and the
 *     UI hides the S Pen affordances.
 *
 * What this is NOT:
 *   - It does NOT call any Samsung SDK methods directly. The actual
 *     SpenSurfaceView / SpenNoteView wiring requires adding the
 *     `com.samsung.android.sdk.pen` SDK as a gradle dependency,
 *     which is deferred until we have a Samsung device to validate
 *     against. The probe here is enough to gate the UI.
 *
 * Phase 3.x will replace the [captureStroke] and [recognizeHandwriting]
 * stubs with real Samsung SDK calls. The public contract stays
 * identical.
 */
@Singleton
class SPenManager @Inject constructor() {

    /**
     * True iff the Samsung S Pen SDK is available on this device.
     *
     * Probes by reflection so the app builds & runs on non-Samsung
     * hardware without crashing.
     */
    val isAvailable: Boolean by lazy {
        runCatching {
            Class.forName("com.samsung.android.sdk.pen.Spen")
            Timber.tag("SPen").i("Samsung S Pen SDK detected")
            true
        }.getOrElse {
            Timber.tag("SPen").d("S Pen SDK not present: ${it.message}")
            false
        }
    }

    /**
     * True iff the device currently has an S Pen paired.
     *
     * Phase 3 stub: returns [isAvailable]. Phase 3.x will query the
     * Samsung SDK's `SpenDeviceManager.isSPenDetected()`.
     */
    suspend fun isSPenDetected(): Boolean = isAvailable

    /**
     * Begin capturing strokes. Returns a hot Flow of [Stroke] events
     * that terminates when [stopCapture] is called or the S Pen lifts.
     *
     * Phase 3 stub: returns an empty Flow. Phase 3.x will route real
     * Samsung SDK callbacks into this Flow.
     */
    fun captureStroke(): kotlinx.coroutines.flow.Flow<Stroke> = kotlinx.coroutines.flow.emptyFlow()

    /** Stop an in-progress capture session. */
    fun stopCapture() {
        Timber.tag("SPen").d("stopCapture (no-op in Phase 3)")
    }
}
