package com.hermes.agent.data.tools

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.hermes.agent.data.appagent.AppAutomationGateway
import com.hermes.agent.data.appagent.AppInteractionSession
import com.hermes.agent.data.appagent.ScreenObservationService
import com.hermes.agent.data.appagent.ScreenSnapshotStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppAgentToolsTest {

    @Test
    fun `AppSwipeTool derives its gesture from actual screen bounds`() = runTest {
        val automation = mockk<AppAutomationGateway>()
        val rootNode = actionableNode(bounds = Rect(0, 0, 100, 100))
        every { automation.activeWindowRoot() } returns rootNode
        every { automation.screenBounds() } returns Rect(0, 0, 1000, 2000)
        every { automation.dispatchSwipe(any(), any(), any(), any(), any()) } returns true
        val snapshots = ScreenSnapshotStore()
        val observations = ScreenObservationService(
            automation,
            snapshots,
            AppInteractionSession().apply { authorize("com.example") },
        )
        val snapshot = snapshots.capture(rootNode, emptyList())

        val result = AppSwipeTool(automation, snapshots, observations).execute(
            mapOf(
                "snapshot_id" to JsonPrimitive(snapshot.id),
                "direction" to JsonPrimitive("up"),
            ),
        )

        assertTrue(result.errorMessage.orEmpty(), result.success)
        verify { automation.dispatchSwipe(500f, 1600f, 500f, 400f, 400L) }
    }

    @Test
    fun `AppAgent mutation tools require confirmation`() {
        val automation = mockk<AppAutomationGateway>(relaxed = true)
        val snapshots = ScreenSnapshotStore()
        val observations = ScreenObservationService(
            automation,
            snapshots,
            AppInteractionSession().apply { authorize("com.example") },
        )

        assertFalse(AppAnalyzeScreenTool(observations).descriptor.requiresConfirmation)
        assertFalse(AppTapTool(automation, snapshots, observations).descriptor.requiresConfirmation)
        assertFalse(AppSwipeTool(automation, snapshots, observations).descriptor.requiresConfirmation)
        assertFalse(AppTypeTool(automation, snapshots, observations).descriptor.requiresConfirmation)
    }

    @Test
    fun `AppTapTool correctly calculates center and calls dispatchTap`() = runTest {
        // Arrange
        val automation = mockk<AppAutomationGateway>()

        val rootNode = mockk<AccessibilityNodeInfo>()
        val buttonNode = mockk<AccessibilityNodeInfo>()

        every { automation.activeWindowRoot() } returns rootNode

        every { rootNode.isVisibleToUser } returns true
        every { rootNode.isClickable } returns false
        every { rootNode.isEditable } returns false
        every { rootNode.isScrollable } returns false
        every { rootNode.isCheckable } returns false
        every { rootNode.childCount } returns 1
        every { rootNode.getChild(0) } returns buttonNode
        every { rootNode.packageName } returns "com.example"
        every { rootNode.windowId } returns 1

        every { buttonNode.isVisibleToUser } returns true
        every { buttonNode.isClickable } returns true
        every { buttonNode.isEditable } returns false
        every { buttonNode.isScrollable } returns false
        every { buttonNode.isCheckable } returns false
        every { buttonNode.childCount } returns 0
        every { buttonNode.text } returns "Submit"
        every { buttonNode.contentDescription } returns null
        every { buttonNode.className } returns "android.widget.Button"
        every { buttonNode.getBoundsInScreen(any()) } answers {
            val rect = arg<Rect>(0)
            rect.set(100, 100, 300, 200) // Center is (200, 150)
        }

        every { automation.dispatchTap(any(), any()) } returns true

        val snapshots = ScreenSnapshotStore()
        val observations = ScreenObservationService(
            automation,
            snapshots,
            AppInteractionSession().apply { authorize("com.example") },
        )
        val snapshot = snapshots.capture(
            rootNode,
            com.hermes.agent.data.appagent.ScreenAnalyzer.analyze(rootNode, null).nodes,
        )
        val tool = AppTapTool(automation, snapshots, observations)
        val args = mapOf(
            "snapshot_id" to JsonPrimitive(snapshot.id),
            "tag" to JsonPrimitive(1),
        )

        // Act
        val result = tool.execute(args)

        // Assert
        assertTrue(result.errorMessage ?: "", result.success)
        verify { automation.dispatchTap(200f, 150f) }
    }

    @Test
    fun `AppTypeTool performs ACTION_SET_TEXT on target node`() = runTest {
        // Arrange
        val automation = mockk<AppAutomationGateway>()

        val rootNode = mockk<AccessibilityNodeInfo>()

        every { automation.activeWindowRoot() } returns rootNode

        every { rootNode.isVisibleToUser } returns true
        every { rootNode.isClickable } returns false
        every { rootNode.isEditable } returns true // Editable!
        every { rootNode.isScrollable } returns false
        every { rootNode.isCheckable } returns false
        every { rootNode.childCount } returns 0
        every { rootNode.packageName } returns "com.example"
        every { rootNode.windowId } returns 1
        every { rootNode.text } returns null
        every { rootNode.contentDescription } returns "Username"
        every { rootNode.className } returns "android.widget.EditText"
        every { rootNode.getBoundsInScreen(any()) } answers {
            val rect = arg<Rect>(0)
            rect.set(10, 10, 100, 50)
        }

        every { automation.setText(rootNode, "Hello World") } returns true

        val snapshots = ScreenSnapshotStore()
        val observations = ScreenObservationService(
            automation,
            snapshots,
            AppInteractionSession().apply { authorize("com.example") },
        )
        val snapshot = snapshots.capture(
            rootNode,
            com.hermes.agent.data.appagent.ScreenAnalyzer.analyze(rootNode, null).nodes,
        )
        val tool = AppTypeTool(automation, snapshots, observations)
        val args = mapOf(
            "snapshot_id" to JsonPrimitive(snapshot.id),
            "tag" to JsonPrimitive(1),
            "text" to JsonPrimitive("Hello World")
        )

        // Act
        val result = tool.execute(args)

        // Assert
        assertTrue(result.errorMessage ?: "", result.success)
        verify { automation.setText(rootNode, "Hello World") }
        assertTrue("Typed text must not be echoed into tool output", "Hello World" !in result.output)
    }

    private fun actionableNode(bounds: Rect): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>()
        every { node.isVisibleToUser } returns true
        every { node.isClickable } returns true
        every { node.isEditable } returns false
        every { node.isScrollable } returns false
        every { node.isCheckable } returns false
        every { node.childCount } returns 0
        every { node.text } returns "Action"
        every { node.contentDescription } returns null
        every { node.className } returns "android.widget.Button"
        every { node.viewIdResourceName } returns null
        every { node.packageName } returns "com.example"
        every { node.windowId } returns 7
        every { node.getBoundsInScreen(any()) } answers { arg<Rect>(0).set(bounds) }
        return node
    }
}
