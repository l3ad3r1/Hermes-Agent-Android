package com.hermes.agent.data.appagent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScreenSnapshotStoreTest {

    @Test
    fun `tag resolves only inside the snapshot that produced it`() {
        val store = ScreenSnapshotStore()
        val root = root(packageName = "com.example.calendar", windowId = 4)
        val first = store.capture(root, listOf(node(tag = 1, left = 10)))
        val second = store.capture(root, listOf(node(tag = 1, left = 500)))

        assertTrue(store.resolve(first.id, 1, root) is SnapshotLookup.Rejected)
        val current = store.resolve(second.id, 1, root) as SnapshotLookup.Found
        assertEquals(500, current.node!!.bounds.left)
    }

    @Test
    fun `snapshot is rejected when the visible package changes`() {
        val store = ScreenSnapshotStore()
        val calendar = root(packageName = "com.google.android.calendar", windowId = 9)
        val snapshot = store.capture(calendar, listOf(node(tag = 1, left = 10)))
        val hermes = root(packageName = "com.hermes.agent.debug", windowId = 9)

        val result = store.resolve(snapshot.id, 1, hermes)

        assertTrue(result is SnapshotLookup.Rejected)
        assertTrue((result as SnapshotLookup.Rejected).message.contains("changed"))
    }

    @Test
    fun `successful action consumption prevents snapshot reuse`() {
        val store = ScreenSnapshotStore()
        val root = root(packageName = "com.example", windowId = 3)
        val snapshot = store.capture(root, listOf(node(tag = 1, left = 10)))

        store.consume(snapshot.id)

        assertTrue(store.resolve(snapshot.id, 1, root) is SnapshotLookup.Rejected)
    }

    private fun root(packageName: String, windowId: Int): AccessibilityNodeInfo =
        mockk<AccessibilityNodeInfo>().also { root ->
            every { root.packageName } returns packageName
            every { root.windowId } returns windowId
        }

    private fun node(tag: Int, left: Int) = UiNode(
        tag = tag,
        bounds = Rect(left, 20, left + 100, 80),
        description = "Control",
        isClickable = true,
        isEditable = false,
        isScrollable = false,
    )
}
