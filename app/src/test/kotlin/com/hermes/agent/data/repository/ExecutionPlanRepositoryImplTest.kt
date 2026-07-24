package com.hermes.agent.data.repository

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hermes.agent.data.local.HermesDatabase
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.model.ExecutionPlan
import com.hermes.agent.domain.model.ExecutionStep
import com.hermes.agent.domain.model.StepStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExecutionPlanRepositoryImplTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        HermesDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val repository = ExecutionPlanRepositoryImpl(db.executionPlanDao())

    @After
    fun closeDatabase() = db.close()

    @Test
    fun `save and observe preserve ordered plan fields`() = runTest {
        repository.save(plan())

        val restored = repository.observeLatest("conversation").first()

        assertNotNull(restored)
        assertEquals(listOf("step-1", "step-2"), restored?.steps?.map { it.id })
        assertEquals(listOf("web_search"), restored?.steps?.first()?.requiredTools)
        assertEquals(listOf("step-1"), restored?.steps?.last()?.dependsOn)
    }

    @Test
    fun `repeated save is idempotent and does not reset progress`() = runTest {
        val plan = plan()
        repository.save(plan)
        repository.markStepRunning("step-1")
        repository.save(plan)

        val restored = repository.get(plan.id)
        assertEquals(2, restored?.steps?.size)
        assertEquals(StepStatus.RUNNING, restored?.steps?.first()?.status)
    }

    @Test
    fun `step transitions persist timestamps and terminal state`() = runTest {
        val plan = plan()
        repository.save(plan)

        repository.markStepRunning("step-1")
        repository.markStepFinished("step-1", StepStatus.FAILED, "network unavailable")

        val restored = repository.get(plan.id)?.steps?.first()
        assertEquals(StepStatus.FAILED, restored?.status)
        assertNotNull(restored?.startedAt)
        assertNotNull(restored?.finishedAt)
        assertEquals("network unavailable", restored?.errorMessage)
    }

    @Test(expected = IllegalStateException::class)
    fun `successful step cannot be overwritten by a later failure`() = runTest {
        repository.save(plan())
        repository.markStepRunning("step-1")
        repository.markStepFinished("step-1", StepStatus.SUCCEEDED)

        repository.markStepFinished("step-1", StepStatus.FAILED, "late failure")
    }

    @Test
    fun `startup reconciliation blocks only interrupted running steps`() = runTest {
        val plan = plan()
        repository.save(plan)
        repository.markStepRunning("step-1")

        assertEquals(1, repository.reconcileInterruptedSteps())

        val restored = repository.get(plan.id)
        assertEquals(StepStatus.BLOCKED, restored?.steps?.first()?.status)
        assertEquals(StepStatus.PENDING, restored?.steps?.last()?.status)
        assertTrue(restored?.steps?.first()?.errorMessage?.contains("Retry") == true)
    }

    @Test
    fun `conversation without plans emits null`() = runTest {
        assertNull(repository.observeLatest("missing").first())
    }

    private fun plan() = ExecutionPlan(
        id = "plan-1",
        conversationId = "conversation",
        userMessage = "research and summarize",
        steps = listOf(
            ExecutionStep(
                id = "step-1",
                agentRole = AgentRole.RESEARCH,
                description = "Research",
                requiredTools = listOf("web_search"),
            ),
            ExecutionStep(
                id = "step-2",
                agentRole = AgentRole.CONVERSATIONAL,
                description = "Summarize",
                dependsOn = listOf("step-1"),
            ),
        ),
        createdAt = 100,
    )
}
