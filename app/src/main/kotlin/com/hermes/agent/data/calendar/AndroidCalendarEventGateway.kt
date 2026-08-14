package com.hermes.agent.data.calendar

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.hermes.agent.domain.calendar.CalendarEventGateway
import com.hermes.agent.domain.calendar.CalendarEventRequest
import com.hermes.agent.domain.calendar.CreatedCalendarEvent
import com.hermes.agent.util.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidCalendarEventGateway @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) : CalendarEventGateway {

    override suspend fun createEvent(
        request: CalendarEventRequest,
    ): Result<CreatedCalendarEvent> = withContext(dispatchers.io) {
        runCatching {
            requireCalendarPermissions()
            val calendar = findWritableCalendar()
                ?: error("No writable calendar is available. Add or enable a calendar account first.")

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendar.id)
                put(CalendarContract.Events.TITLE, request.title)
                put(CalendarContract.Events.DTSTART, request.start.toEpochMilli())
                put(CalendarContract.Events.DTEND, request.end.toEpochMilli())
                put(CalendarContract.Events.EVENT_TIMEZONE, request.timeZoneId)
                put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
                request.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: error("The calendar provider rejected the event.")
            val eventId = uri.lastPathSegment?.toLongOrNull()
                ?: error("The calendar provider returned an invalid event identifier.")
            CreatedCalendarEvent(eventId, calendar.name)
        }
    }

    private fun requireCalendarPermissions() {
        val readGranted = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        val writeGranted = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        check(readGranted && writeGranted) {
            "Calendar access is not granted. Allow both calendar permissions in Android settings."
        }
    }

    private fun findWritableCalendar(): CalendarChoice? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        val selection =
            "${CalendarContract.Calendars.VISIBLE}=1 AND " +
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val order = "${CalendarContract.Calendars.IS_PRIMARY} DESC, " +
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"

        return context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            args,
            order,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            CalendarChoice(
                id = cursor.getLong(0),
                name = cursor.getString(1)?.takeIf(String::isNotBlank) ?: "Calendar",
            )
        }
    }

    private data class CalendarChoice(val id: Long, val name: String)
}
