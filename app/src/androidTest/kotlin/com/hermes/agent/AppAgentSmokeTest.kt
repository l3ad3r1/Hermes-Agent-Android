package com.hermes.agent

import android.app.UiAutomation
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.agent.data.appagent.ScreenAnalyzer
import com.hermes.agent.data.appagent.AppInteractionSession
import com.hermes.agent.data.appagent.ScreenObservation
import com.hermes.agent.data.appagent.ScreenObservationService
import com.hermes.agent.data.appagent.ScreenSnapshotStore
import com.hermes.agent.data.appagent.UiAutomationAppAutomationGateway
import com.hermes.agent.data.tools.AppAnalyzeScreenTool
import com.hermes.agent.data.tools.AppTapTool
import com.hermes.agent.data.tools.AppTypeTool
import com.hermes.agent.debug.AppAgentFixtureActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device contract test for ScreenAnalyzer and the AppAgent tools.
 *
 * UiAutomation is deliberately the test backend. The production
 * AccessibilityService lifecycle gets a separate manual acceptance check;
 * instrumentation no longer has to race that service's process rebind.
 */
@RunWith(AndroidJUnit4::class)
class AppAgentSmokeTest {

    @Test
    fun analyzeTapAndTypeThroughUiAutomation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = requireNotNull(
            instrumentation.getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
            ),
        ) { "UiAutomation could not connect to the accessibility subsystem." }
        val automation = UiAutomationAppAutomationGateway(
            uiAutomation = uiAutomation,
            context = instrumentation.targetContext,
        )
        val snapshots = ScreenSnapshotStore()
        val interactionSession = AppInteractionSession()
        val observations = ScreenObservationService(automation, snapshots, interactionSession)

        ActivityScenario.launch(AppAgentFixtureActivity::class.java).use { scenario ->
            val root = awaitRoot(automation)
            interactionSession.authorize(root.packageName?.toString().orEmpty())
            val initialAnalysis = ScreenAnalyzer.analyze(root, null)
            val tapNode = initialAnalysis.nodes.firstOrNull {
                AppAgentFixtureActivity.TAP_TARGET_DESCRIPTION in it.description
            }
            val textNode = initialAnalysis.nodes.firstOrNull {
                AppAgentFixtureActivity.TEXT_TARGET_DESCRIPTION in it.description
            }
            assertNotNull("Fixture tap target was not visible to UiAutomation", tapNode)
            assertNotNull("Fixture text target was not visible to UiAutomation", textNode)

            val analyzeResult = AppAnalyzeScreenTool(observations).execute(emptyMap())
            assertTrue(analyzeResult.errorMessage.orEmpty(), analyzeResult.success)
            assertTrue(
                "Analyze output did not contain the fixture control",
                analyzeResult.output.contains(AppAgentFixtureActivity.TAP_TARGET_DESCRIPTION),
            )

            val tapObservation = observations.capture() as ScreenObservation.Captured
            val snapshotTapNode = tapObservation.snapshot.nodes.first {
                AppAgentFixtureActivity.TAP_TARGET_DESCRIPTION in it.description
            }
            val tapResult = AppTapTool(automation, snapshots, observations).execute(
                mapOf(
                    "snapshot_id" to JsonPrimitive(tapObservation.snapshot.id),
                    "tag" to JsonPrimitive(snapshotTapNode.tag),
                ),
            )
            assertTrue(tapResult.errorMessage.orEmpty(), tapResult.success)
            awaitActivityState(scenario) { activity ->
                activity.statusText() == AppAgentFixtureActivity.STATUS_TAPPED
            }

            val typeObservation = observations.capture() as ScreenObservation.Captured
            val refreshedTextNode = typeObservation.snapshot.nodes.firstOrNull {
                AppAgentFixtureActivity.TEXT_TARGET_DESCRIPTION in it.description
            }
            assertNotNull("Fixture text target disappeared after tap", refreshedTextNode)
            val typeResult = AppTypeTool(automation, snapshots, observations).execute(
                mapOf(
                    "snapshot_id" to JsonPrimitive(typeObservation.snapshot.id),
                    "tag" to JsonPrimitive(refreshedTextNode!!.tag),
                    "text" to JsonPrimitive(AppAgentFixtureActivity.TYPED_TEXT),
                ),
            )
            assertTrue(typeResult.errorMessage.orEmpty(), typeResult.success)
            assertTrue(
                "Tool output leaked the entered text",
                AppAgentFixtureActivity.TYPED_TEXT !in typeResult.output,
            )
            awaitActivityState(scenario) { activity ->
                activity.enteredText() == AppAgentFixtureActivity.TYPED_TEXT
            }
        }
    }

    private suspend fun awaitRoot(
        automation: UiAutomationAppAutomationGateway,
    ): android.view.accessibility.AccessibilityNodeInfo {
        repeat(DEVICE_WAIT_ATTEMPTS) {
            automation.activeWindowRoot()?.let { return it }
            delay(DEVICE_WAIT_MS)
        }
        error("UiAutomation did not expose an active-window root.")
    }

    private suspend fun awaitActivityState(
        scenario: ActivityScenario<AppAgentFixtureActivity>,
        predicate: (AppAgentFixtureActivity) -> Boolean,
    ) {
        repeat(DEVICE_WAIT_ATTEMPTS) {
            var matches = false
            scenario.onActivity { matches = predicate(it) }
            if (matches) return
            delay(DEVICE_WAIT_MS)
        }
        var finalValue = false
        scenario.onActivity { finalValue = predicate(it) }
        assertEquals("Timed out waiting for the AppAgent action to reach the fixture", true, finalValue)
    }

    private companion object {
        const val DEVICE_WAIT_ATTEMPTS = 40
        const val DEVICE_WAIT_MS = 100L
    }
}
