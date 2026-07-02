package com.hermes.agent.domain.repository

import com.hermes.agent.domain.model.KanbanStatus
import com.hermes.agent.domain.model.KanbanTicket
import com.hermes.agent.domain.model.TicketPriority
import kotlinx.coroutines.flow.Flow

interface KanbanRepository {
    fun observe(): Flow<List<KanbanTicket>>
    suspend fun get(id: String): KanbanTicket?

    /** Oldest tickets currently in [KanbanStatus.TODO] — used by the background agent loop. */
    suspend fun nextTodo(): KanbanTicket?

    /** Live count of [KanbanStatus.TODO] tickets. Room re-emits on every board
     *  change, so this doubles as the background agent's wake signal — the
     *  service sleeps until the count goes positive instead of polling. */
    fun observeTodoCount(): Flow<Int>

    suspend fun create(
        title: String,
        body: String = "",
        priority: TicketPriority = TicketPriority.MEDIUM,
        assignee: String? = null,
        tags: List<String> = emptyList(),
    ): KanbanTicket

    suspend fun moveTo(id: String, status: KanbanStatus)
    suspend fun complete(id: String, result: String?)
    suspend fun delete(id: String)

    /** Insert a few example tickets the first time the board is opened (mirrors the prototype's demo data). */
    suspend fun seedIfEmpty()
}
