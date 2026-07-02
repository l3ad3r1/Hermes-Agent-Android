package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.KanbanTicketDao
import com.hermes.agent.data.local.entity.KanbanTicketEntity
import com.hermes.agent.domain.model.KanbanStatus
import com.hermes.agent.domain.model.KanbanTicket
import com.hermes.agent.domain.model.TicketPriority
import com.hermes.agent.domain.repository.KanbanRepository
import com.hermes.agent.util.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KanbanRepositoryImpl @Inject constructor(
    private val dao: KanbanTicketDao,
) : KanbanRepository {

    override fun observe(): Flow<List<KanbanTicket>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): KanbanTicket? = dao.getById(id)?.toDomain()

    override suspend fun nextTodo(): KanbanTicket? =
        dao.getByStatus(KanbanStatus.TODO.name).firstOrNull()?.toDomain()

    override fun observeTodoCount(): Flow<Int> =
        dao.observeCountByStatus(KanbanStatus.TODO.name)

    override suspend fun create(
        title: String,
        body: String,
        priority: TicketPriority,
        assignee: String?,
        tags: List<String>,
    ): KanbanTicket {
        val now = System.currentTimeMillis()
        val ticket = KanbanTicket(
            id = "t_" + IdGenerator.newId().take(8),
            title = title,
            body = body,
            status = KanbanStatus.TODO,
            assignee = assignee,
            priority = priority,
            tags = tags,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(KanbanTicketEntity.fromDomain(ticket))
        return ticket
    }

    override suspend fun moveTo(id: String, status: KanbanStatus) {
        dao.updateStatus(id, status.name, System.currentTimeMillis())
    }

    override suspend fun complete(id: String, result: String?) {
        val now = System.currentTimeMillis()
        dao.complete(id, KanbanStatus.DONE.name, result, now, now)
    }

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun seedIfEmpty() {
        if (dao.count() > 0) return
        SEED_TICKETS.forEach { dao.upsert(KanbanTicketEntity.fromDomain(it)) }
    }

    private companion object {
        val now = System.currentTimeMillis()
        val SEED_TICKETS = listOf(
            KanbanTicket(
                id = "t_seed0001",
                title = "Implement Android browser automation",
                body = "Use the WebView/CDP bridge to let the browser tool drive pages on-device.",
                status = KanbanStatus.IN_PROGRESS,
                assignee = "hermes",
                priority = TicketPriority.HIGH,
                tags = listOf("android", "browser", "automation"),
                createdAt = now - 86_400_000,
                updatedAt = now - 3_600_000,
            ),
            KanbanTicket(
                id = "t_seed0002",
                title = "Wire scheduled cron deliveries to Telegram",
                body = "Route CronWorker output through the notify tool to connected platforms.",
                status = KanbanStatus.TODO,
                priority = TicketPriority.MEDIUM,
                tags = listOf("gateway", "telegram", "cron"),
                createdAt = now - 72_000_000,
                updatedAt = now - 72_000_000,
            ),
            KanbanTicket(
                id = "t_seed0003",
                title = "Cache frequently accessed skills",
                body = "Add a lightweight cache in front of SkillDao for large skill sets.",
                status = KanbanStatus.TODO,
                priority = TicketPriority.LOW,
                tags = listOf("performance", "database"),
                createdAt = now - 36_000_000,
                updatedAt = now - 36_000_000,
            ),
            KanbanTicket(
                id = "t_seed0004",
                title = "Configure Hilt dependency injection",
                body = "Hilt modules wired across ViewModels, services, and workers.",
                status = KanbanStatus.DONE,
                assignee = "hermes",
                priority = TicketPriority.HIGH,
                result = "DI fully configured across all layers.",
                tags = listOf("setup", "di"),
                createdAt = now - 172_800_000,
                updatedAt = now - 160_000_000,
                completedAt = now - 160_000_000,
            ),
        )
    }
}
