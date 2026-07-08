package com.hermes.agent.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CronTimingTest {

    private val zone = ZoneId.of("UTC")

    /** Wed 2026-07-08 10:30 UTC. */
    private val wednesdayMorning: Long =
        ZonedDateTime.of(2026, 7, 8, 10, 30, 0, 0, zone).toInstant().toEpochMilli()

    private fun delayHours(expr: String, from: Long = wednesdayMorning): Double =
        CronTiming.initialDelayMillis(expr, from, zone) / 3_600_000.0

    @Test
    fun `daily at 8am anchors to NEXT 8am, not 24h from now`() {
        // 10:30 Wed → next 8:00 is Thu, 21.5h away (old behavior: first run ~immediate).
        assertEquals(21.5, delayHours("0 8 * * *"), 0.01)
    }

    @Test
    fun `daily at 6pm today is still ahead`() {
        assertEquals(7.5, delayHours("0 18 * * *"), 0.01)
    }

    @Test
    fun `morning and evening presets no longer collapse to the same schedule`() {
        assertTrue(delayHours("0 8 * * *") != delayHours("0 18 * * *"))
    }

    @Test
    fun `hourly anchors to the next top of hour`() {
        assertEquals(0.5, delayHours("0 * * * *"), 0.01) // 10:30 → 11:00
        assertEquals(60L, CronTiming.periodMinutes("0 * * * *"))
    }

    @Test
    fun `weekly monday 8am from wednesday waits until next monday`() {
        // Wed 10:30 → Mon 8:00 = 4 days 21.5h = 117.5h
        assertEquals(117.5, delayHours("0 8 * * 1"), 0.01)
        assertEquals(7 * 24 * 60L, CronTiming.periodMinutes("0 8 * * 1"))
    }

    @Test
    fun `weekday filter gates saturday but allows wednesday`() {
        val saturday = ZonedDateTime.of(2026, 7, 11, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertFalse(CronTiming.shouldRunNow("0 8 * * 1-5", saturday, zone))
        assertTrue(CronTiming.shouldRunNow("0 8 * * 1-5", wednesdayMorning, zone))
    }

    @Test
    fun `weekdays keeps a daily period with runtime gating`() {
        assertEquals(24 * 60L, CronTiming.periodMinutes("0 8 * * 1-5"))
    }

    @Test
    fun `cron sunday 0 and 7 both map to ISO sunday`() {
        val sunday = ZonedDateTime.of(2026, 7, 12, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertTrue(CronTiming.shouldRunNow("0 8 * * 0", sunday, zone))
        assertTrue(CronTiming.shouldRunNow("0 8 * * 7", sunday, zone))
        assertFalse(CronTiming.shouldRunNow("0 8 * * 0", wednesdayMorning, zone))
    }

    @Test
    fun `unparseable expression falls back to always-run daily with no anchor`() {
        assertEquals(24 * 60L, CronTiming.periodMinutes("*/15 something weird"))
        assertEquals(0L, CronTiming.initialDelayMillis("*/15 * * * *", wednesdayMorning, zone))
        assertTrue(CronTiming.shouldRunNow("*/15 * * * *", wednesdayMorning, zone))
    }
}
