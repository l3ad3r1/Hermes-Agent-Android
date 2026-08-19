package com.hermes.agent.data.appagent

import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ScreenObservation {
    data class Captured(val snapshot: ScreenSnapshot, val modelText: String) : ScreenObservation
    data class Unavailable(val message: String) : ScreenObservation
}

/** Captures and formats one atomic, model-visible Android UI state. */
@Singleton
class ScreenObservationService @Inject constructor(
    private val automation: AppAutomationGateway,
    private val snapshots: ScreenSnapshotStore,
    private val interactionSession: AppInteractionSession,
) {
    suspend fun capture(
        settleDelayMs: Long = 0L,
        redactedText: String? = null,
    ): ScreenObservation {
        if (settleDelayMs > 0) delay(settleDelayMs)
        val root = automation.activeWindowRoot()
            ?: return ScreenObservation.Unavailable(
                "App control is unavailable. Enable the Hermes accessibility service, " +
                    "then unlock the device and try again.",
            )
        val visiblePackage = runCatching { root.packageName?.toString().orEmpty() }
            .getOrDefault("")
        interactionSession.rejectionReason(visiblePackage)?.let { reason ->
            snapshots.clear()
            return ScreenObservation.Unavailable(reason)
        }
        val analysis = ScreenAnalyzer.analyze(root, null)
        val snapshot = snapshots.capture(root, analysis.nodes)
        return ScreenObservation.Captured(snapshot, snapshot.toModelText(redactedText))
    }

    private fun ScreenSnapshot.toModelText(redactedText: String?): String = buildString {
        appendLine("Screen snapshot $id for ${identity.packageName.ifEmpty { "the visible app" }}:")
        if (nodes.isEmpty()) {
            appendLine("No actionable elements found on screen.")
        } else {
            nodes.forEach { node ->
                val safeDescription = redactedText
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { node.description.replace(it, REDACTED_TEXT_MARKER) }
                    ?: node.description
                appendLine(
                    "- Tag ${node.tag}: [$safeDescription] " +
                        "(clickable: ${node.isClickable}, editable: ${node.isEditable}) " +
                        "at bounds ${node.bounds.toShortString()}",
                )
            }
        }
        appendLine(
            "Use this snapshot_id ($id) with app_tap, app_type, or app_swipe. " +
                "After an action, use only the new snapshot returned by that action.",
        )
    }

    private companion object {
        const val REDACTED_TEXT_MARKER = "[entered text]"
    }
}
