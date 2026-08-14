package com.hermes.agent.data.tools

import com.hermes.agent.domain.calendar.CalendarEventGateway
import com.hermes.agent.domain.calendar.CalendarEventRequest
import com.hermes.agent.domain.calendar.CreatedCalendarEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CalendarToolTest {

    @Test
    fun `creates a real gateway request with exact offset and duration`() = runTest {
        val gateway = FakeCalendarEventGateway()
        val result = CalendarTool(gateway).execute(
            mapOf(
                "title" to JsonPrimitive("Anshkosh Metting with Ramandeep"),
                "start_iso" to JsonPrimitive("2026-08-14T15:00:00+05:30"),
                "duration_minutes" to JsonPrimitive(60),
            ),
        )

        assertTrue(result.errorMessage.orEmpty(), result.success)
        val request = gateway.lastRequest ?: error("gateway was not called")
        assertEquals("Anshkosh Metting with Ramandeep", request.title)
        assertEquals(Instant.parse("2026-08-14T09:30:00Z"), request.start)
        assertEquals(Instant.parse("2026-08-14T10:30:00Z"), request.end)
        assertTrue(result.output.contains("id=42"))
    }

    @Test
    fun `rejects invalid duration before writing`() = runTest {
        val gateway = FakeCalendarEventGateway()
        val result = CalendarTool(gateway).execute(
            mapOf(
                "title" to JsonPrimitive("Meeting"),
                "start_iso" to JsonPrimitive("2026-08-14T15:00:00+05:30"),
                "duration_minutes" to JsonPrimitive(0),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("duration_minutes"))
        assertEquals(null, gateway.lastRequest)
    }

    @Test
    fun `surfaces calendar provider failures without fake success`() = runTest {
        val gateway = FakeCalendarEventGateway(Result.failure(IllegalStateException("permission denied")))
        val result = CalendarTool(gateway).execute(
            mapOf(
                "title" to JsonPrimitive("Meeting"),
                "start_iso" to JsonPrimitive("2026-08-14T15:00:00+05:30"),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("permission denied"))
        assertFalse(result.output.contains("event created", ignoreCase = true))
    }

    private class FakeCalendarEventGateway(
        private val result: Result<CreatedCalendarEvent> =
            Result.success(CreatedCalendarEvent(42L, "Primary")),
    ) : CalendarEventGateway {
        var lastRequest: CalendarEventRequest? = null

        override suspend fun createEvent(request: CalendarEventRequest): Result<CreatedCalendarEvent> {
            lastRequest = request
            return result
        }
    }
}
