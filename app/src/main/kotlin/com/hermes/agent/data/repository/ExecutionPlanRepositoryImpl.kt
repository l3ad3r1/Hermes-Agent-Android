package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.ExecutionPlanDao
import com.hermes.agent.data.local.entity.stepEntities
import com.hermes.agent.data.local.entity.toEntity
import com.hermes.agent.domain.model.ExecutionPlan
import com.hermes.agent.domain.model.StepStatus
import com.hermes.agent.domain.repository.ExecutionPlanRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExecutionPlanRepositoryImpl @Inject constructor(
    private val dao: ExecutionPlanDao,
) : ExecutionPlanRepository {
    override fun observeLatest(conversationId: String): Flow<ExecutionPlan?> =
        dao.observeLatest(conversationId).map { it?.toDomain() }

    override suspend fun get(planId: String): ExecutionPlan? = dao.getById(planId)?.toDomain()

    override suspend fun save(plan: ExecutionPlan) {
        dao.insertPlanIfAbsent(plan.toEntity(), plan.stepEntities())
    }

    override suspend fun markStepRunning(stepId: String) {
        check(dao.markRunning(stepId, System.currentTimeMillis()) == 1) {
            "plan step '$stepId' was not pending or does not exist"
        }
    }

    override suspend fun markStepFinished(
        stepId: String,
        status: StepStatus,
        errorMessage: String?,
    ) {
        require(status in TERMINAL_STATUSES) { "step status must be terminal: $status" }
        check(dao.markFinished(stepId, status.name, System.currentTimeMillis(), errorMessage) == 1) {
            "plan step '$stepId' was not running or does not exist"
        }
    }

    override suspend fun reconcileInterruptedSteps(): Int = dao.blockInterruptedSteps(
        finishedAt = System.currentTimeMillis(),
        reason = "Execution was interrupted when Jeeves stopped. Retry this step to continue.",
    )

    companion object {
        private val TERMINAL_STATUSES = setOf(
            StepStatus.SUCCEEDED,
            StepStatus.FAILED,
            StepStatus.SKIPPED,
            StepStatus.BLOCKED,
            StepStatus.CANCELLED,
        )
    }
}
