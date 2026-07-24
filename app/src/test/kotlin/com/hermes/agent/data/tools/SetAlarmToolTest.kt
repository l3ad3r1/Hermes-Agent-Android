package com.hermes.agent.data.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sassybutler.alarm.Alarm
import com.sassybutler.alarm.AlarmScheduler
import com.sassybutler.alarm.AlarmStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the `set_alarm` agent tool -> Sassy Butler's [AlarmScheduler] / [AlarmStore].
 *
 * Uses the real [AlarmStore] (SharedPreferences under Robolectric) so the persistence
 * contract is exercised, and mocks only the scheduler (which talks to AlarmManager).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetAlarmToolTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scheduler = mockk<AlarmScheduler>(relaxed = true)
    private val tool = SetAlarmTool(context, scheduler)

    private fun args(vararg pairs: Pair<String, JsonElement>): Map<String, JsonElement> =
        pairs.toMap()

    @Test
    fun `sets an alarm, persists it, and reports the time`() = runTest {
        val result = tool.execute(
            args(
                "hour" to JsonPrimitive(7),
                "minute" to JsonPrimitive(30),
                "label" to JsonPrimitive("Standup"),
            )
        )

        assertTrue(result.errorMessage, result.success)
        assertTrue(result.output, result.output.contains("07:30"))
        assertTrue(result.output, result.output.contains("Standup"))

        val stored = AlarmStore.all(context)
        assertEquals(1, stored.size)
        assertEquals(7, stored[0].hour)
        assertEquals(30, stored[0].minute)
        assertEquals("Standup", stored[0].label)
        assertTrue(stored[0].enabled)
        assertTrue("agent alarms are one-shot", stored[0].days.isEmpty())

        verify { scheduler.schedule(any<Alarm>()) }
    }

    /**
     * The alarm must be in AlarmStore *before* it is scheduled: AlarmReceiver re-registers
     * stored alarms after a reboot, so scheduling an unstored alarm would silently lose it.
     */
    @Test
    fun `persists to AlarmStore before scheduling`() = runTest {
        var storedWhenScheduled = -1
        every { scheduler.schedule(any<Alarm>()) } answers {
            storedWhenScheduled = AlarmStore.all(context).size
            0
        }

        tool.execute(args("hour" to JsonPrimitive(6), "minute" to JsonPrimitive(0)))

        assertEquals("alarm was scheduled before it was persisted", 1, storedWhenScheduled)
    }

    /**
     * Butler's own UI assigns sequential ids via AlarmStore.nextId() (1, 2, 3, ...).
     * The agent's time-derived id must live in a disjoint range: an id collision makes
     * AlarmStore.upsert silently REPLACE the user's alarm, and — because the PendingIntent
     * request code is the alarm id — clobbers its scheduled intent too. The midnight hour
     * is where hour*100+minute lands right in the UI's id space ("12:05am" -> 5).
     */
    @Test
    fun `midnight-hour agent alarm does not replace a UI-created alarm`() = runTest {
        (1..5).forEach { i ->
            AlarmStore.upsert(
                context,
                Alarm(id = i, hour = 8, minute = i, label = "UI $i", enabled = true, days = emptySet()),
            )
        }

        tool.execute(args("hour" to JsonPrimitive(0), "minute" to JsonPrimitive(5)))

        val stored = AlarmStore.all(context)
        assertEquals("agent alarm must be added, not swallow a UI alarm", 6, stored.size)
        assertTrue(
            "user's alarm id=5 was silently replaced by the agent's",
            stored.any { it.id == 5 && it.label == "UI 5" },
        )
        assertTrue("agent's 00:05 alarm missing", stored.any { it.hour == 0 && it.minute == 5 })
    }

    @Test
    fun `accepts hour and minute sent as quoted strings`() = runTest {
        val result = tool.execute(
            args("hour" to JsonPrimitive("9"), "minute" to JsonPrimitive("05"))
        )
        assertTrue(result.errorMessage, result.success)
        assertEquals(9, AlarmStore.all(context)[0].hour)
        assertEquals(5, AlarmStore.all(context)[0].minute)
    }

    @Test
    fun `label defaults when omitted`() = runTest {
        tool.execute(args("hour" to JsonPrimitive(8), "minute" to JsonPrimitive(15)))
        assertEquals("Alarm", AlarmStore.all(context)[0].label)
    }

    @Test
    fun `out-of-range hour is rejected and nothing is scheduled`() = runTest {
        val result = tool.execute(args("hour" to JsonPrimitive(25), "minute" to JsonPrimitive(0)))

        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("hour"))
        assertTrue(AlarmStore.all(context).isEmpty())
        verify(exactly = 0) { scheduler.schedule(any<Alarm>()) }
    }

    @Test
    fun `out-of-range minute is rejected`() = runTest {
        val result = tool.execute(args("hour" to JsonPrimitive(7), "minute" to JsonPrimitive(60)))
        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("minute"))
    }

    @Test
    fun `non-numeric hour is a tool error, not a crash`() = runTest {
        val result = tool.execute(args("hour" to JsonPrimitive("noon"), "minute" to JsonPrimitive(0)))
        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("hour"))
    }

    @Test
    fun `missing minute is a tool error`() = runTest {
        val result = tool.execute(args("hour" to JsonPrimitive(7)))
        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("minute"))
    }
}
