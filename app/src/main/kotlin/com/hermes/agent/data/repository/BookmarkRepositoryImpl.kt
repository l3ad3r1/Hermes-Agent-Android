package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.BookmarkDao
import com.hermes.agent.data.local.entity.BookmarkEntity
import com.hermes.agent.domain.model.Bookmark
import com.hermes.agent.domain.repository.BookmarkRepository
import com.hermes.agent.util.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookmarkRepositoryImpl @Inject constructor(
    private val dao: BookmarkDao,
) : BookmarkRepository {

    override fun observeAll(): Flow<List<Bookmark>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): Bookmark? = dao.getById(id)?.toDomain()

    override suspend fun create(url: String, title: String, note: String, tags: List<String>): Bookmark {
        val bookmark = Bookmark(
            id = "bm_" + IdGenerator.newId().take(8),
            url = url,
            title = title,
            note = note,
            tags = tags,
        )
        dao.upsert(BookmarkEntity.fromDomain(bookmark))
        return bookmark
    }

    override suspend fun update(id: String, title: String?, note: String?, tags: List<String>?) {
        val tagsJson = tags?.let { Json.encodeToString(it) }
        dao.update(id, title, note, tagsJson)
    }

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun search(query: String, limit: Int): List<Bookmark> =
        dao.search(query, limit).map { it.toDomain() }

    override suspend fun getByTag(tag: String): List<Bookmark> =
        dao.getByTag(tag).map { it.toDomain() }
}
