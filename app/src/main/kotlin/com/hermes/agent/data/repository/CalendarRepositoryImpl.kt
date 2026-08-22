package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.CalendarEventDao
import com.hermes.agent.data.local.entity.CalendarEventEntity
import com.hermes.agent.domain.model.CalendarEvent
import com.hermes.agent.domain.repository.CalendarRepository
import com.hermes.agent.util.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarRepositoryImpl @Inject constructor(
    private val dao: CalendarEventDao,
) : CalendarRepository {

    override fun observeAll(): Flow<List<CalendarEvent>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): CalendarEvent? = dao.getById(id)?.toDomain()

    override suspend fun create(
        title: String,
        description: String,
        startMs: Long,
        endMs: Long,
        allDay: Boolean,
        sourceCalendar: String,
        location: String?,
        reminderMinutes: Int,
    ): CalendarEvent {
        val event = CalendarEvent(
            id = "ce_" + IdGenerator.newId().take(8),
            title = title,
            description = description,
            startMs = startMs,
            endMs = endMs,
            allDay = allDay,
            sourceCalendar = sourceCalendar,
            location = location,
            reminderMinutes = reminderMinutes,
        )
        dao.upsert(CalendarEventEntity.fromDomain(event))
        return event
    }

    override suspend fun update(
        id: String,
        title: String?,
        description: String?,
        startMs: Long?,
        endMs: Long?,
        allDay: Boolean?,
        location: String?,
    ) {
        dao.update(id, title, description, startMs, endMs, allDay, location)
    }

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun getUpcoming(limit: Int): List<CalendarEvent> =
        dao.getUpcoming(limit = limit).map { it.toDomain() }

    override suspend fun getByDateRange(startMs: Long, endMs: Long): List<CalendarEvent> =
        dao.getByDateRange(startMs, endMs).map { it.toDomain() }
}
