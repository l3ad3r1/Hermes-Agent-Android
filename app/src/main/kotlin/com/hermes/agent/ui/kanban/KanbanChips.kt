package com.hermes.agent.ui.kanban

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.agent.domain.model.KanbanStatus
import com.hermes.agent.domain.model.TicketPriority
import com.hermes.agent.ui.theme.KanbanBlocked
import com.hermes.agent.ui.theme.KanbanCancelled
import com.hermes.agent.ui.theme.KanbanDone
import com.hermes.agent.ui.theme.KanbanInProgress
import com.hermes.agent.ui.theme.KanbanReview
import com.hermes.agent.ui.theme.KanbanTodo
import com.hermes.agent.ui.theme.PriorityCritical
import com.hermes.agent.ui.theme.PriorityHigh
import com.hermes.agent.ui.theme.PriorityLow
import com.hermes.agent.ui.theme.PriorityMedium

@Composable
fun PriorityChip(priority: TicketPriority, modifier: Modifier = Modifier) {
    val (color, label) = when (priority) {
        TicketPriority.CRITICAL -> PriorityCritical to "Critical"
        TicketPriority.HIGH -> PriorityHigh to "High"
        TicketPriority.MEDIUM -> PriorityMedium to "Medium"
        TicketPriority.LOW -> PriorityLow to "Low"
    }
    AssistChip(
        onClick = { },
        label = { Text(label, style = MaterialTheme.typography.labelSmall, color = color) },
        modifier = modifier.padding(horizontal = 4.dp),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    )
}

@Composable
fun StatusChip(status: KanbanStatus, modifier: Modifier = Modifier) {
    val (container, label) = when (status) {
        KanbanStatus.TODO -> KanbanTodo to "Todo"
        KanbanStatus.IN_PROGRESS -> KanbanInProgress to "In Progress"
        KanbanStatus.REVIEW -> KanbanReview to "Review"
        KanbanStatus.BLOCKED -> KanbanBlocked to "Blocked"
        KanbanStatus.DONE -> KanbanDone to "Done"
        KanbanStatus.CANCELLED -> KanbanCancelled to "Cancelled"
    }
    AssistChip(
        onClick = { },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = modifier.padding(horizontal = 4.dp),
        colors = AssistChipDefaults.assistChipColors(containerColor = container.copy(alpha = 0.25f)),
    )
}

fun KanbanStatus.color() = when (this) {
    KanbanStatus.TODO -> KanbanTodo
    KanbanStatus.IN_PROGRESS -> KanbanInProgress
    KanbanStatus.REVIEW -> KanbanReview
    KanbanStatus.BLOCKED -> KanbanBlocked
    KanbanStatus.DONE -> KanbanDone
    KanbanStatus.CANCELLED -> KanbanCancelled
}
