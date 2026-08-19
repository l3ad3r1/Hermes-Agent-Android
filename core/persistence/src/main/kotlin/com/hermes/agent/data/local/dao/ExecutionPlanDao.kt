package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.hermes.agent.data.local.entity.ExecutionPlanEntity
import com.hermes.agent.data.local.entity.ExecutionPlanWithSteps
import com.hermes.agent.data.local.entity.ExecutionStepEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExecutionPlanDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlan(plan: ExecutionPlanEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSteps(steps: List<ExecutionStepEntity>)

    @Transaction
    suspend fun insertPlanIfAbsent(plan: ExecutionPlanEntity, steps: List<ExecutionStepEntity>) {
        if (insertPlan(plan) != -1L) insertSteps(steps)
    }

    @Transaction
    @Query(
        "SELECT * FROM execution_plans WHERE conversationId = :conversationId " +
            "ORDER BY createdAt DESC LIMIT 1",
    )
    fun observeLatest(conversationId: String): Flow<ExecutionPlanWithSteps?>

    @Transaction
    @Query("SELECT * FROM execution_plans WHERE id = :planId LIMIT 1")
    suspend fun getById(planId: String): ExecutionPlanWithSteps?

    @Query(
        "UPDATE execution_steps SET statusName = 'RUNNING', startedAt = :startedAt, " +
            "finishedAt = NULL, errorMessage = NULL " +
            "WHERE id = :stepId AND statusName IN ('PENDING', 'RUNNING')",
    )
    suspend fun markRunning(stepId: String, startedAt: Long): Int

    @Query(
        "UPDATE execution_steps SET statusName = :statusName, finishedAt = :finishedAt, " +
            "errorMessage = :errorMessage WHERE id = :stepId AND statusName = 'RUNNING'",
    )
    suspend fun markFinished(
        stepId: String,
        statusName: String,
        finishedAt: Long,
        errorMessage: String?,
    ): Int

    @Query(
        "UPDATE execution_steps SET statusName = 'BLOCKED', finishedAt = :finishedAt, " +
            "errorMessage = :reason WHERE statusName = 'RUNNING'",
    )
    suspend fun blockInterruptedSteps(finishedAt: Long, reason: String): Int
}
