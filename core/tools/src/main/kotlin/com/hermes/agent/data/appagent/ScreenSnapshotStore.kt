package com.hermes.agent.data.appagent

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import javax.inject.Inject
import javax.inject.Singleton

data class ScreenIdentity(
    val packageName: String,
    val windowId: Int,
)

data class ScreenSnapshot(
    val id: Long,
    val identity: ScreenIdentity,
    val nodes: List<UiNode>,
    val capturedAtElapsedMs: Long,
)

sealed interface SnapshotLookup {
    data class Found(val snapshot: ScreenSnapshot, val node: UiNode?) : SnapshotLookup
    data class Rejected(val message: String) : SnapshotLookup
}

/**
 * Owns the single model-visible Android screen snapshot.
 *
 * Tags only have meaning inside their originating snapshot. Keeping this
 * state outside individual tools prevents a tool from silently rebuilding
 * the tree and assigning an old tag to a different control.
 */
@Singleton
class ScreenSnapshotStore @Inject constructor() {
    private var nextId = 1L
    private var latest: ScreenSnapshot? = null

    @Synchronized
    fun capture(root: AccessibilityNodeInfo, nodes: List<UiNode>): ScreenSnapshot {
        val snapshot = ScreenSnapshot(
            id = nextId++,
            identity = root.screenIdentity(),
            nodes = nodes.map { it.copy(bounds = Rect(it.bounds)) },
            capturedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
        latest = snapshot
        return snapshot
    }

    @Synchronized
    fun validate(snapshotId: Long, currentRoot: AccessibilityNodeInfo): SnapshotLookup {
        val snapshot = latest
            ?: return SnapshotLookup.Rejected(
                "No active screen snapshot. Run app_analyze_screen before interacting.",
            )
        if (snapshot.id != snapshotId) {
            return SnapshotLookup.Rejected(
                "Snapshot $snapshotId is no longer active. Use the latest snapshot from app_analyze_screen.",
            )
        }
        if (SystemClock.elapsedRealtime() - snapshot.capturedAtElapsedMs > SNAPSHOT_TTL_MS) {
            latest = null
            return SnapshotLookup.Rejected(
                "Snapshot $snapshotId expired. Analyze the screen again before interacting.",
            )
        }

        val currentIdentity = currentRoot.screenIdentity()
        if (!snapshot.identity.matches(currentIdentity)) {
            latest = null
            return SnapshotLookup.Rejected(
                "The visible app or window changed after snapshot $snapshotId. Analyze the screen again.",
            )
        }
        return SnapshotLookup.Found(snapshot, null)
    }

    @Synchronized
    fun resolve(
        snapshotId: Long,
        tag: Int,
        currentRoot: AccessibilityNodeInfo,
    ): SnapshotLookup = when (val validation = validate(snapshotId, currentRoot)) {
        is SnapshotLookup.Rejected -> validation
        is SnapshotLookup.Found -> {
            val node = validation.snapshot.nodes.firstOrNull { it.tag == tag }
                ?: return SnapshotLookup.Rejected(
                    "Tag $tag does not exist in snapshot $snapshotId. Analyze the screen again.",
                )
            SnapshotLookup.Found(validation.snapshot, node)
        }
    }

    @Synchronized
    fun consume(snapshotId: Long) {
        if (latest?.id == snapshotId) latest = null
    }

    @Synchronized
    fun clear() {
        latest = null
    }

    private fun AccessibilityNodeInfo.screenIdentity(): ScreenIdentity = ScreenIdentity(
        packageName = runCatching { packageName?.toString().orEmpty() }.getOrDefault(""),
        windowId = runCatching { windowId }.getOrDefault(UNKNOWN_WINDOW_ID),
    )

    private fun ScreenIdentity.matches(other: ScreenIdentity): Boolean {
        val packageMatches = packageName.isEmpty() || other.packageName.isEmpty() ||
            packageName == other.packageName
        val windowMatches = windowId == UNKNOWN_WINDOW_ID || other.windowId == UNKNOWN_WINDOW_ID ||
            windowId == other.windowId
        return packageMatches && windowMatches
    }

    private companion object {
        const val SNAPSHOT_TTL_MS = 60_000L
        const val UNKNOWN_WINDOW_ID = -1
    }
}
