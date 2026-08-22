package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.MoodEntryDao
import com.hermes.agent.data.local.entity.MoodEntryEntity
import com.hermes.agent.domain.model.MoodEntry
import com.hermes.agent.domain.model.MoodLevel
import com.hermes.agent.domain.repository.MoodRepository
import com.hermes.agent.util.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MoodRepositoryImpl @Inject constructor(
    private val dao: MoodEntryDao,
) : MoodRepository {

    override fun observeAll(): Flow<List<MoodEntry>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): MoodEntry? = dao.getById(id)?.toDomain()

    override suspend fun create(
        dateMs: Long,
        mood: MoodLevel,
        intensity: Int,
        note: String,
        tags: List<String>,
    ): MoodEntry {
        val entry = MoodEntry(
            id = "md_" + IdGenerator.newId().take(8),
            dateMs = dateMs,
            mood = mood,
            intensity = intensity,
            note = note,
            tags = tags,
        )
        dao.upsert(MoodEntryEntity.fromDomain(entry))
        return entry
    }

    override suspend fun update(
        id: String,
        mood: MoodLevel?,
        intensity: Int?,
        note: String?,
        tags: List<String>?,
    ) {
        val current = dao.getById(id)?.toDomain() ?: return
        val updated = current.copy(
            mood = mood ?: current.mood,
            intensity = intensity ?: current.intensity,
            note = note ?: current.note,
            tags = tags ?: current.tags,
        )
        dao.upsert(MoodEntryEntity.fromDomain(updated))
    }

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun getForDate(dateMs: Long): MoodEntry? = dao.getForDate(dateMs)?.toDomain()

    override suspend fun getWeeklyStats(days: Int): Map<MoodLevel, Int> {
        val startMs = System.currentTimeMillis() - days * 86_400_000L
        return dao.getInRange(startMs, System.currentTimeMillis())
            .groupBy({ it.mood }, { 1 })
            .mapValues { it.value.sum() }
            .mapKeys { MoodLevel.fromName(it.key) }
    }

    override suspend fun getTrend(days: Int): List<MoodEntry> {
        val startMs = System.currentTimeMillis() - days * 86_400_000L
        return dao.getInRange(startMs, System.currentTimeMillis()).map { it.toDomain() }
    }

    override suspend fun getMostFrequentMood(days: Int): MoodLevel? {
        val startMs = System.currentTimeMillis() - days * 86_400_000L
        val entries = dao.getInRange(startMs, System.currentTimeMillis())
        if (entries.isEmpty()) return null
        return entries.groupBy { it.mood }
            .maxByOrNull { it.value.size }
            ?.key
            ?.let { MoodLevel.fromName(it) }
    }
}
