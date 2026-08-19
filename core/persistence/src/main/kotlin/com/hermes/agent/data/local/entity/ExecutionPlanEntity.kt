package com.hermes.agent.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.model.ExecutionPlan
import com.hermes.agent.domain.model.ExecutionStep
import com.hermes.agent.domain.model.StepStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(
    tableName = "execution_plans",
    indices = [Index(value = ["conversationId", "createdAt"])],
)
data class ExecutionPlanEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val userMessage: String,
    val createdAt: Long,
    val approved: Boolean,
)

@Entity(
    tableName = "execution_steps",
    foreignKeys = [
        ForeignKey(
            entity = ExecutionPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("planId"), Index(value = ["planId", "position"], unique = true)],
)
data class ExecutionStepEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val position: Int,
    val agentRoleName: String,
    val description: String,
    val requiredToolsJson: String,
    val dependsOnJson: String,
    val statusName: String,
    val startedAt: Long?,
    val finishedAt: Long?,
    val toolCallIdsJson: String,
    val errorMessage: String?,
)

data class ExecutionPlanWithSteps(
    @Embedded val plan: ExecutionPlanEntity,
    @Relation(parentColumn = "id", entityColumn = "planId")
    val steps: List<ExecutionStepEntity>,
) {
    fun toDomain(): ExecutionPlan = ExecutionPlan(
        id = plan.id,
        conversationId = plan.conversationId,
        userMessage = plan.userMessage,
        steps = steps.sortedBy { it.position }.map(ExecutionStepEntity::toDomain),
        createdAt = plan.createdAt,
        approved = plan.approved,
    )
}

fun ExecutionPlan.toEntity() = ExecutionPlanEntity(
    id = id,
    conversationId = conversationId,
    userMessage = userMessage,
    createdAt = createdAt,
    approved = approved,
)

fun ExecutionPlan.stepEntities(): List<ExecutionStepEntity> = steps.mapIndexed { position, step ->
    ExecutionStepEntity(
        id = step.id,
        planId = id,
        position = position,
        agentRoleName = step.agentRole.name,
        description = step.description,
        requiredToolsJson = Json.encodeToString(step.requiredTools),
        dependsOnJson = Json.encodeToString(step.dependsOn),
        statusName = step.status.name,
        startedAt = step.startedAt,
        finishedAt = step.finishedAt,
        toolCallIdsJson = Json.encodeToString(step.toolCallIds),
        errorMessage = step.errorMessage,
    )
}

private fun ExecutionStepEntity.toDomain() = ExecutionStep(
    id = id,
    agentRole = runCatching { AgentRole.valueOf(agentRoleName) }.getOrDefault(AgentRole.DEFAULT),
    description = description,
    requiredTools = decodeStringList(requiredToolsJson),
    dependsOn = decodeStringList(dependsOnJson),
    status = runCatching { StepStatus.valueOf(statusName) }.getOrDefault(StepStatus.FAILED),
    startedAt = startedAt,
    finishedAt = finishedAt,
    toolCallIds = decodeStringList(toolCallIdsJson),
    errorMessage = errorMessage,
)

private fun decodeStringList(value: String): List<String> =
    runCatching { Json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())
