package com.hermes.agent.data.plugins

import com.hermes.agent.data.spen.SPenManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SPenManagerTest {

    private val manager = SPenManager()

    @Test
    fun `isAvailable returns false on non-Samsung test env`() {
        // The test JVM doesn't have the Samsung S Pen SDK on the classpath.
        assertFalse(manager.isAvailable)
    }

    @Test
    fun `isSPenDetected matches isAvailable`() = runTest {
        assertEquals(manager.isAvailable, manager.isSPenDetected())
    }

    @Test
    fun `captureStroke returns an empty flow in Phase 3`() = runTest {
        val flow = manager.captureStroke()
        val collected = mutableListOf<com.hermes.agent.data.spen.Stroke>()
        flow.collect { collected.add(it) }
        assertTrue(collected.isEmpty())
    }

    @Test
    fun `stopCapture does not throw`() {
        // Just verify no exception is thrown.
        manager.stopCapture()
    }
}
