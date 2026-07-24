package com.hermes.agent.ui.kanban

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.hermes.agent.ui.components.DestructiveActionDialog
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.agent.domain.model.KanbanStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketDetailScreen(
    ticketId: String,
    onNavigateBack: () -> Unit,
    viewModel: TicketDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(ticketId) { viewModel.load(ticketId) }
    val ticket by viewModel.ticket.collectAsState()

    var showDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ticket") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                },
            )
        },
    ) { padding ->
        val t = ticket
        if (t == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(t.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(t.id, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row {
                StatusChip(t.status)
                PriorityChip(t.priority)
            }
            t.assignee?.let {
                Spacer(Modifier.height(8.dp))
                AssistChip(onClick = {}, label = { Text("Assignee: @$it") })
            }
            if (t.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    t.tags.forEach { tag -> AssistChip(onClick = {}, label = { Text("#$tag", style = MaterialTheme.typography.labelSmall) }) }
                }
            }
            if (t.body.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(t.body, style = MaterialTheme.typography.bodyMedium)
            }
            t.result?.let {
                Spacer(Modifier.height(16.dp))
                Text("Result", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(24.dp))
            Text("Move to", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                KanbanStatus.entries.forEach { status ->
                    FilterChip(
                        selected = status == t.status,
                        onClick = { viewModel.move(status) },
                        label = { Text(status.label()) },
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        DestructiveActionDialog(
            title = "Delete Ticket",
            message = "Are you sure you want to delete this ticket?",
            confirmLabel = "Delete",
            onConfirm = {
                showDeleteConfirm = false
                viewModel.delete(onDeleted = onNavigateBack)
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}

private fun KanbanStatus.label() = when (this) {
    KanbanStatus.TODO -> "Todo"
    KanbanStatus.IN_PROGRESS -> "In Progress"
    KanbanStatus.REVIEW -> "Review"
    KanbanStatus.BLOCKED -> "Blocked"
    KanbanStatus.DONE -> "Done"
    KanbanStatus.CANCELLED -> "Cancelled"
}
