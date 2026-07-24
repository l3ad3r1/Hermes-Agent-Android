package com.hermes.agent.data.proactive

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationCaptureStoreTest {

    private val store = NotificationCaptureStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `capture is off by default`() {
        assertFalse(store.captureEnabled)
    }

    @Test
    fun `entries round-trip and filter by time`() {
        store.add(CapturedNotification("com.app.a", "Old", "old text", timestamp = 1_000))
        store.add(CapturedNotification("com.app.b", "New", "new text", timestamp = 5_000))

        val since = store.entriesSince(2_000)

        assertEquals(1, since.size)
        assertEquals("New", since.first().title)
    }

    @Test
    fun `store is bounded to the newest entries`() {
        repeat(NotificationCaptureStore.MAX_ENTRIES + 10) { i ->
            store.add(CapturedNotification("com.app", "n$i", "", timestamp = i.toLong()))
        }

        val all = store.entriesSince(0)

        assertEquals(NotificationCaptureStore.MAX_ENTRIES, all.size)
        assertTrue(all.first().title == "n10")
    }

    @Test
    fun `clear removes everything`() {
        store.add(CapturedNotification("com.app", "x", "y", timestamp = 1))
        store.clear()
        assertEquals(0, store.entriesSince(0).size)
    }
}
