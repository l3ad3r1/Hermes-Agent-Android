package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.NoteDao
import com.hermes.agent.data.local.entity.NoteEntity
import com.hermes.agent.domain.model.Note
import com.hermes.agent.domain.repository.NotesRepository
import com.hermes.agent.util.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotesRepositoryImpl @Inject constructor(
    private val dao: NoteDao,
) : NotesRepository {

    override fun observeAll(): Flow<List<Note>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): Note? = dao.getById(id)?.toDomain()

    override suspend fun create(
        title: String,
        content: String,
        tags: List<String>,
        category: String,
        folder: String?,
    ): Note {
        val now = System.currentTimeMillis()
        val note = Note(
            id = "n_" + IdGenerator.newId().take(8),
            title = title,
            content = content,
            tags = tags,
            category = category,
            folder = folder,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(NoteEntity.fromDomain(note))
        return note
    }

    override suspend fun update(
        id: String,
        title: String?,
        content: String?,
        tags: List<String>?,
        starred: Boolean?,
    ) {
        val current = dao.getById(id) ?: return
        val updated = current.copy(
            title = title ?: current.title,
            content = content ?: current.content,
            tagsJson = tags?.let { Json.encodeToString(it) } ?: current.tagsJson,
            updatedAt = System.currentTimeMillis(),
        )
        if (starred != null) updated.copy(isStarred = starred)
        dao.upsert(updated)
    }

    override suspend fun toggleStar(id: String) {
        val current = dao.getById(id) ?: return
        dao.upsert(current.copy(isStarred = !current.isStarred, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun search(query: String, limit: Int): List<Note> =
        dao.search(query, limit).map { it.toDomain() }

    override suspend fun getByCategory(category: String): List<Note> =
        dao.getByCategory(category).map { it.toDomain() }
}
