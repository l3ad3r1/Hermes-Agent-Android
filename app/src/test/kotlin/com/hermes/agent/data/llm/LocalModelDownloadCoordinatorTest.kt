package com.hermes.agent.data.llm

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelDownloadCoordinatorTest {

    @Test
    fun `queued and running work recover as active downloads`() {
        val queued = modelDownloadSnapshot(WorkInfo.State.ENQUEUED, 0, "")
        val running = modelDownloadSnapshot(WorkInfo.State.RUNNING, 37, "")

        assertTrue(queued.isDownloading)
        assertTrue(running.isDownloading)
        assertEquals(0.37f, running.progress)
    }

    @Test
    fun `successful work is complete and no longer active`() {
        val snapshot = modelDownloadSnapshot(WorkInfo.State.SUCCEEDED, 0, "")

        assertFalse(snapshot.isDownloading)
        assertEquals(1f, snapshot.progress)
        assertEquals("", snapshot.error)
    }

    @Test
    fun `failed work surfaces its actionable worker error`() {
        val snapshot = modelDownloadSnapshot(
            WorkInfo.State.FAILED,
            81,
            "Free storage and try again.",
        )

        assertFalse(snapshot.isDownloading)
        assertEquals("Free storage and try again.", snapshot.error)
    }

    @Test
    fun `progress is clamped to a valid percentage`() {
        assertEquals(1f, modelDownloadSnapshot(WorkInfo.State.RUNNING, 400, "").progress)
        assertEquals(0f, modelDownloadSnapshot(WorkInfo.State.RUNNING, -20, "").progress)
    }
}
