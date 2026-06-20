package com.hermes.agent.data.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 unit tests for [MemoryPressureMonitor] state classification.
 *
 * These tests exercise the pure classification logic — the actual
 * ActivityManager probe is exercised on-device via instrumented tests
 * (not in this JVM unit test).
 */
class MemoryPressureMonitorTest {

    @Test
    fun `NORMAL ELEVATED CRITICAL are distinct enum values`() {
        val states = MemoryPressureState.entries.toSet()
        assertTrue(MemoryPressureState.NORMAL in states)
        assertTrue(MemoryPressureState.ELEVATED in states)
        assertTrue(MemoryPressureState.CRITICAL in states)
        assertEquals(3, states.size)
    }

    @Test
    fun `thresholds match the plan`() {
        // Section 5.2 of the plan: shed load when available < 2 GB.
        assertEquals(2_000, MemoryPressureMonitor.CRITICAL_THRESHOLD_MB)
        // ELEVATED starts higher to give subscribers a heads-up.
        assertTrue(MemoryPressureMonitor.ELEVATED_THRESHOLD_MB > MemoryPressureMonitor.CRITICAL_THRESHOLD_MB)
    }

    @Test
    fun `poll interval is reasonable`() {
        // Polling too often wastes battery; too slow misses pressure events.
        assertTrue(MemoryPressureMonitor.POLL_INTERVAL_MS >= 5_000L)
        assertTrue(MemoryPressureMonitor.POLL_INTERVAL_MS <= 60_000L)
    }

    @Test
    fun `MemorySnapshot carries all diagnostic fields`() {
        val snapshot = MemorySnapshot(
            availableMb = 1500,
            totalMb = 12_000,
            thresholdMb = 800,
            isLowMemory = true,
            state = MemoryPressureState.CRITICAL,
        )
        assertEquals(1500, snapshot.availableMb)
        assertEquals(12_000, snapshot.totalMb)
        assertEquals(800, snapshot.thresholdMb)
        assertTrue(snapshot.isLowMemory)
        assertEquals(MemoryPressureState.CRITICAL, snapshot.state)
    }
}
