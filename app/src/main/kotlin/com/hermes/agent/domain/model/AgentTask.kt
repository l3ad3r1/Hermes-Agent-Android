package com.hermes.agent.domain.model

enum class AgentTaskStatus(val label: String) {
    QUEUED("Queued"),
    RUNNING("Running"),
    COMPLETED("Completed"),
    FAILED("Failed"),
}

data class AgentTask(
    val id: String,
    val label: String,
    val prompt: String,
    val status: AgentTaskStatus = AgentTaskStatus.QUEUED,
    val result: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
)
