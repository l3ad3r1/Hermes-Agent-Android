package com.hermes.agent.domain.repository

import com.hermes.agent.domain.model.ExecutionPlan
import com.hermes.agent.domain.model.StepStatus
import kotlinx.coroutines.flow.Flow

interface ExecutionPlanRepository {
    fun observeLatest(conversationId: String): Flow<ExecutionPlan?>
    suspend fun get(planId: String): ExecutionPlan?
    suspend fun save(plan: ExecutionPlan)
    suspend fun markStepRunning(stepId: String)
    suspend fun markStepFinished(stepId: String, status: StepStatus, errorMessage: String? = null)
    suspend fun reconcileInterruptedSteps(): Int
}
