package com.hermes.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.agent.data.local.HermesDatabase
import com.hermes.agent.data.repository.KanbanRepositoryImpl
import com.hermes.agent.data.tools.KanbanTool
import com.hermes.agent.di.DatabaseModule
import com.hermes.agent.domain.model.KanbanStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device instrumentation smoke test for the Kanban Board and KanbanTool.
 * Runs directly on the connected hardware device (Samsung Galaxy S24 Ultra).
 */
@RunWith(AndroidJUnit4::class)
class KanbanOnDeviceSmokeTest {

    private lateinit var database: HermesDatabase
    private lateinit var repository: KanbanRepositoryImpl
    private lateinit var tool: KanbanTool

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = DatabaseModule.provideDatabase(context)
        repository = KanbanRepositoryImpl(database.kanbanTicketDao())
        tool = KanbanTool(repository)
    }

    @Test
    fun testKanbanBatchDecompositionAndLifecycleOnDevice() {
        runBlocking {
            // 1. Break down a complex project into batch Kanban tickets
        val ticketsJson = JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("Task 1: Setup E-Commerce Backend"),
                        "body" to JsonPrimitive("Initialize database schema and product catalog APIs"),
                        "priority" to JsonPrimitive("HIGH"),
                        "tags" to JsonArray(listOf(JsonPrimitive("backend"), JsonPrimitive("database"))),
                    ),
                ),
                JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("Task 2: Build Checkout & Payments"),
                        "body" to JsonPrimitive("Integrate Stripe and Google Pay gateways"),
                        "priority" to JsonPrimitive("CRITICAL"),
                        "tags" to JsonArray(listOf(JsonPrimitive("payment"), JsonPrimitive("security"))),
                    ),
                ),
                JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("Task 3: User Acceptance & QA"),
                        "body" to JsonPrimitive("End-to-end cart checkout flow testing"),
                        "priority" to JsonPrimitive("MEDIUM"),
                        "tags" to JsonArray(listOf(JsonPrimitive("qa"))),
                    ),
                ),
            ),
        )

        val batchResult = tool.execute(
            mapOf(
                "action" to JsonPrimitive("create_batch"),
                "tickets" to ticketsJson,
            ),
        )

        assertTrue(batchResult.errorMessage.orEmpty(), batchResult.success)
        assertTrue(batchResult.output.contains("Successfully created 3 Kanban tickets"))

        // 2. Verify tickets exist in Room DB on the physical device
        val allTickets = repository.observe().first()
        val createdTask1 = allTickets.firstOrNull { it.title == "Task 1: Setup E-Commerce Backend" }
        val createdTask2 = allTickets.firstOrNull { it.title == "Task 2: Build Checkout & Payments" }
        val createdTask3 = allTickets.firstOrNull { it.title == "Task 3: User Acceptance & QA" }

        assertNotNull("Task 1 was not persisted in device Room DB", createdTask1)
        assertNotNull("Task 2 was not persisted in device Room DB", createdTask2)
        assertNotNull("Task 3 was not persisted in device Room DB", createdTask3)

        // 3. Move Task 1 to IN_PROGRESS and then to DONE with result
        val moveInProgressResult = tool.execute(
            mapOf(
                "action" to JsonPrimitive("move"),
                "id" to JsonPrimitive(createdTask1!!.id),
                "status" to JsonPrimitive("IN_PROGRESS"),
            ),
        )
        assertTrue(moveInProgressResult.success)

        val moveDoneResult = tool.execute(
            mapOf(
                "action" to JsonPrimitive("move"),
                "id" to JsonPrimitive(createdTask1.id),
                "status" to JsonPrimitive("DONE"),
                "result" to JsonPrimitive("Database schema migration 13 applied and catalog API endpoints ready"),
            ),
        )
        assertTrue(moveDoneResult.success)

        // 4. Verify completed ticket details via 'get'
        val getResult = tool.execute(
            mapOf(
                "action" to JsonPrimitive("get"),
                "id" to JsonPrimitive(createdTask1.id),
            ),
        )
        assertTrue(getResult.success)
        assertTrue(getResult.output.contains("DONE"))
        assertTrue(getResult.output.contains("Database schema migration 13"))

        // 5. Test 'list' with DONE filter
        val listDoneResult = tool.execute(
            mapOf(
                "action" to JsonPrimitive("list"),
                "status" to JsonPrimitive("DONE"),
            ),
        )
        assertTrue(listDoneResult.success)
        assertTrue(listDoneResult.output.contains(createdTask1.title))

        // 6. Clean up test tickets
        tool.execute(mapOf("action" to JsonPrimitive("delete"), "id" to JsonPrimitive(createdTask1.id)))
        tool.execute(mapOf("action" to JsonPrimitive("delete"), "id" to JsonPrimitive(createdTask2!!.id)))
        tool.execute(mapOf("action" to JsonPrimitive("delete"), "id" to JsonPrimitive(createdTask3!!.id)))
        }
    }
}
