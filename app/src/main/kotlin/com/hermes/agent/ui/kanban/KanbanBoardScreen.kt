package com.hermes.agent.ui.kanban

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.agent.domain.model.KanbanStatus
import com.hermes.agent.domain.model.KanbanTicket
import com.hermes.agent.domain.model.TicketPriority
import com.hermes.agent.service.AgentServiceController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanBoardScreen(
    onTicketClick: (String) -> Unit,
    viewModel: KanbanViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val serviceRunning by AgentServiceController.running.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Kanban Board",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    // Start/stop the background agent that works the board.
                    IconButton(onClick = {
                        if (serviceRunning) AgentServiceController.stop(context)
                        else AgentServiceController.start(context)
                    }) {
                        Icon(
                            if (serviceRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = if (serviceRunning) "Stop agent" else "Start agent",
                            tint = if (serviceRunning) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = viewModel::toggleViewMode) {
                        Icon(
                            if (uiState.viewMode == KanbanViewMode.BOARD) Icons.Outlined.ViewAgenda
                            else Icons.Outlined.ViewColumn,
                            contentDescription = "Toggle view",
                        )
                    }
                    IconButton(onClick = viewModel::showCreateDialog) {
                        Icon(Icons.Filled.Add, contentDescription = "Create ticket")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            StatusTabRow(uiState.filterStatus, viewModel::filterByStatus)
            if (uiState.viewMode == KanbanViewMode.BOARD) {
                KanbanBoard(uiState.ticketsByStatus, onTicketClick)
            } else {
                TicketList(uiState.filteredTickets, onTicketClick)
            }
        }
    }

    if (uiState.showCreateDialog) {
        CreateTicketDialog(
            onDismiss = viewModel::hideCreateDialog,
            onCreate = viewModel::createTicket,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusTabRow(selected: KanbanStatus?, onSelect: (KanbanStatus?) -> Unit) {
    val tabs: List<Pair<String, KanbanStatus?>> = listOf(
        "All" to null,
        "Todo" to KanbanStatus.TODO,
        "In Progress" to KanbanStatus.IN_PROGRESS,
        "Review" to KanbanStatus.REVIEW,
        "Blocked" to KanbanStatus.BLOCKED,
        "Done" to KanbanStatus.DONE,
        "Cancelled" to KanbanStatus.CANCELLED,
    )
    val index = tabs.indexOfFirst { it.second == selected }.coerceAtLeast(0)
    ScrollableTabRow(selectedTabIndex = index, edgePadding = 0.dp, modifier = Modifier.fillMaxWidth()) {
        tabs.forEach { (label, status) ->
            Tab(selected = selected == status, onClick = { onSelect(status) }) {
                Text(label, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun KanbanBoard(
    ticketsByStatus: Map<KanbanStatus, List<KanbanTicket>>,
    onTicketClick: (String) -> Unit,
) {
    val columns = listOf(
        KanbanStatus.TODO,
        KanbanStatus.IN_PROGRESS,
        KanbanStatus.REVIEW,
        KanbanStatus.BLOCKED,
        KanbanStatus.DONE,
    )
    Row(
        modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        columns.forEach { status ->
            KanbanColumn(
                title = status.label(),
                color = status.color(),
                tickets = ticketsByStatus[status].orEmpty(),
                onTicketClick = onTicketClick,
            )
        }
    }
}

@Composable
private fun KanbanColumn(
    title: String,
    color: Color,
    tickets: List<KanbanTicket>,
    onTicketClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(280.dp)
            .background(color.copy(alpha = 0.10f), MaterialTheme.shapes.medium)
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
            Badge(containerColor = color) { Text(tickets.size.toString()) }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tickets) { KanbanCard(it) { onTicketClick(it.id) } }
        }
    }
}

@Composable
private fun KanbanCard(ticket: KanbanTicket, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(ticket.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            ticket.assignee?.let {
                AssistChip(onClick = {}, label = { Text("@$it", style = MaterialTheme.typography.labelSmall) })
                Spacer(Modifier.height(4.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PriorityChip(ticket.priority)
                Text(
                    ticket.id,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TicketList(tickets: List<KanbanTicket>, onTicketClick: (String) -> Unit) {
    if (tickets.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Outlined.Inbox,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(16.dp))
            Text("No tickets found", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tickets) { ticket ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onTicketClick(ticket.id) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(ticket.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 2)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(ticket.status)
                        PriorityChip(ticket.priority)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateTicketDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, body: String, priority: TicketPriority, tags: List<String>) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(TicketPriority.MEDIUM) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New ticket") },
        text = {
            Column {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = body, onValueChange = { body = it },
                    label = { Text("Description") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tags, onValueChange = { tags = it },
                    label = { Text("Tags (comma-separated)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions.Default, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Priority", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TicketPriority.entries.forEach { p ->
                        TextButton(onClick = { priority = p }) {
                            Text(
                                p.name.lowercase().replaceFirstChar { it.uppercase() },
                                fontWeight = if (p == priority) FontWeight.Bold else FontWeight.Normal,
                                color = if (p == priority) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(
                        title, body, priority,
                        tags.split(",").map { it.trim() }.filter { it.isNotBlank() },
                    )
                },
                enabled = title.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun KanbanStatus.label() = when (this) {
    KanbanStatus.TODO -> "Todo"
    KanbanStatus.IN_PROGRESS -> "In Progress"
    KanbanStatus.REVIEW -> "Review"
    KanbanStatus.BLOCKED -> "Blocked"
    KanbanStatus.DONE -> "Done"
    KanbanStatus.CANCELLED -> "Cancelled"
}
