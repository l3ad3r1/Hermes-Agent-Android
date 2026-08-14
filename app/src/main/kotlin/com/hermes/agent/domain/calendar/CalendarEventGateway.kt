package com.hermes.agent.domain.calendar

import java.time.Instant

/** A calendar event that has already been resolved to exact instants. */
data class CalendarEventRequest(
    val title: String,
    val start: Instant,
    val end: Instant,
    val timeZoneId: String,
    val location: String? = null,
)

data class CreatedCalendarEvent(
    val id: Long,
    val calendarName: String,
)

/** Platform boundary used by calendar tools. */
interface CalendarEventGateway {
    suspend fun createEvent(request: CalendarEventRequest): Result<CreatedCalendarEvent>
}
