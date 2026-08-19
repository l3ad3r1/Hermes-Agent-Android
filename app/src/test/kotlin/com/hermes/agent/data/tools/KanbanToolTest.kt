package com.hermes.agent.data.tools

import com.hermes.agent.domain.model.KanbanStatus
import com.hermes.agent.domain.model.KanbanTicket
import com.hermes.agent.domain.model.TicketPriority
import com.hermes.agent.domain.repository.KanbanRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class KanbanToolTest {

    private lateinit var fakeRepository: FakeKanbanRepository
    private lateinit var tool: KanbanTool

    @Before
    fun setUp() {
        fakeRepository = FakeKanbanRepository()
        tool = KanbanTool(fakeRepository)
    }

    @Test
    fun `creates a single ticket with title, priority and tags`() = runTest {
        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("create"),
                "title" to JsonPrimitive("Design Landing Page"),
                "body" to JsonPrimitive("Create wireframes and visual specs"),
                "priority" to JsonPrimitive("HIGH"),
                "tags" to JsonArray(listOf(JsonPrimitive("ui"), JsonPrimitive("design"))),
            ),
        )

        assertTrue(result.errorMessage.orEmpty(), result.success)
        assertTrue(result.output.contains("Design Landing Page"))
        assertTrue(result.output.contains("HIGH"))

        val tickets = fakeRepository.tickets.value
        assertEquals(1, tickets.size)
        val created = tickets.first()
        assertEquals("Design Landing Page", created.title)
        assertEquals("Create wireframes and visual specs", created.body)
        assertEquals(TicketPriority.HIGH, created.priority)
        assertEquals(listOf("ui", "design"), created.tags)
        assertEquals(KanbanStatus.TODO, created.status)
    }

    @Test
    fun `create_batch breaks down a complex task into multiple tickets`() = runTest {
        val ticketsJson = JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("Phase 1: Architecture & Data Models"),
                        "body" to JsonPrimitive("Define Room entities and repositories"),
                        "priority" to JsonPrimitive("HIGH"),
                        "tags" to JsonArray(listOf(JsonPrimitive("backend"))),
                    ),
                ),
                JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("Phase 2: UI Implementation"),
                        "body" to JsonPrimitive("Compose screens and viewmodels"),
                        "priority" to JsonPrimitive("MEDIUM"),
                        "tags" to JsonArray(listOf(JsonPrimitive("frontend"))),
                    ),
                ),
                JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("Phase 3: Automated Testing & Verification"),
                        "body" to JsonPrimitive("Write unit and instrumentation tests"),
                        "priority" to JsonPrimitive("HIGH"),
                        "tags" to JsonArray(listOf(JsonPrimitive("qa"))),
                    ),
                ),
            ),
        )

        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("create_batch"),
                "tickets" to ticketsJson,
            ),
        )

        assertTrue(result.errorMessage.orEmpty(), result.success)
        assertTrue(result.output.contains("Successfully created 3 Kanban tickets"))

        val tickets = fakeRepository.tickets.value
        assertEquals(3, tickets.size)
        assertEquals("Phase 1: Architecture & Data Models", tickets[0].title)
        assertEquals("Phase 2: UI Implementation", tickets[1].title)
        assertEquals("Phase 3: Automated Testing & Verification", tickets[2].title)
    }

    @Test
    fun `list returns all tickets grouped by status`() = runTest {
        fakeRepository.create("Ticket 1", priority = TicketPriority.LOW)
        val inProgress = fakeRepository.create("Ticket 2", priority = TicketPriority.HIGH)
        fakeRepository.moveTo(inProgress.id, KanbanStatus.IN_PROGRESS)

        val result = tool.execute(
            mapOf("action" to JsonPrimitive("list")),
        )

        assertTrue(result.errorMessage.orEmpty(), result.success)
        assertTrue(result.output.contains("TODO"))
        assertTrue(result.output.contains("Ticket 1"))
        assertTrue(result.output.contains("IN_PROGRESS"))
        assertTrue(result.output.contains("Ticket 2"))
    }

    @Test
    fun `list filters by status`() = runTest {
        fakeRepository.create("Ticket 1")
        val done = fakeRepository.create("Ticket 2")
        fakeRepository.complete(done.id, "Done successfully")

        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("list"),
                "status" to JsonPrimitive("DONE"),
            ),
        )

        assertTrue(result.errorMessage.orEmpty(), result.success)
        assertTrue(result.output.contains("Ticket 2"))
        assertFalse(result.output.contains("Ticket 1"))
    }

    @Test
    fun `get retrieves full details of a specific ticket`() = runTest {
        val created = fakeRepository.create(
            title = "Test Search Feature",
            body = "Verify FTS4 indexing works across all messages",
            priority = TicketPriority.CRITICAL,
            tags = listOf("search", "database"),
        )

        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("get"),
                "id" to JsonPrimitive(created.id),
            ),
        )

        assertTrue(result.errorMessage.orEmpty(), result.success)
        assertTrue(result.output.contains("Test Search Feature"))
        assertTrue(result.output.contains("Verify FTS4 indexing"))
        assertTrue(result.output.contains("CRITICAL"))
        assertTrue(result.output.contains("search, database"))
    }

    @Test
    fun `move updates status and completes ticket with result`() = runTest {
        val created = fakeRepository.create("Execute DB migration")

        val moveResult = tool.execute(
            mapOf(
                "action" to JsonPrimitive("move"),
                "id" to JsonPrimitive(created.id),
                "status" to JsonPrimitive("DONE"),
                "result" to JsonPrimitive("Migration 12_13 executed cleanly"),
            ),
        )

        assertTrue(moveResult.errorMessage.orEmpty(), moveResult.success)
        val updated = fakeRepository.get(created.id)
        assertNotNull(updated)
        assertEquals(KanbanStatus.DONE, updated!!.status)
        assertEquals("Migration 12_13 executed cleanly", updated.result)
    }

    @Test
    fun `delete removes a ticket from the board`() = runTest {
        val created = fakeRepository.create("Obsolete task")

        val deleteResult = tool.execute(
            mapOf(
                "action" to JsonPrimitive("delete"),
                "id" to JsonPrimitive(created.id),
            ),
        )

        assertTrue(deleteResult.errorMessage.orEmpty(), deleteResult.success)
        assertEquals(0, fakeRepository.tickets.value.size)
    }

    @Test
    fun `rejects missing action parameter`() = runTest {
        val result = tool.execute(emptyMap())
        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("action"))
    }

    private class FakeKanbanRepository : KanbanRepository {
        val tickets = MutableStateFlow<List<KanbanTicket>>(emptyList())

        override fun observe(): Flow<List<KanbanTicket>> = tickets

        override suspend fun get(id: String): KanbanTicket? =
            tickets.value.firstOrNull { it.id == id }

        override suspend fun nextTodo(): KanbanTicket? =
            tickets.value.firstOrNull { it.status == KanbanStatus.TODO }

        override fun observeTodoCount(): Flow<Int> =
            MutableStateFlow(tickets.value.count { it.status == KanbanStatus.TODO })

        override suspend fun create(
            title: String,
            body: String,
            priority: TicketPriority,
            assignee: String?,
            tags: List<String>,
        ): KanbanTicket {
            val ticket = KanbanTicket(
                id = UUID.randomUUID().toString().take(8),
                title = title,
                body = body,
                priority = priority,
                assignee = assignee,
                tags = tags,
                status = KanbanStatus.TODO,
            )
            tickets.value = tickets.value + ticket
            return ticket
        }

        override suspend fun moveTo(id: String, status: KanbanStatus) {
            tickets.value = tickets.value.map {
                if (it.id == id) it.copy(status = status, updatedAt = System.currentTimeMillis())
                else it
            }
        }

        override suspend fun complete(id: String, result: String?) {
            tickets.value = tickets.value.map {
                if (it.id == id) it.copy(
                    status = KanbanStatus.DONE,
                    result = result,
                    completedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                ) else it
            }
        }

        override suspend fun delete(id: String) {
            tickets.value = tickets.value.filterNot { it.id == id }
        }

        override suspend fun seedIfEmpty() {}
    }
}
