package com.hermes.agent.ui.delegate

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.domain.model.AgentTask
import com.hermes.agent.domain.model.AgentTaskStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DelegateScreen(viewModel: DelegateViewModel = hiltViewModel()) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Delegate") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "Delegate task")
            }
        },
    ) { padding ->
        if (tasks.isEmpty()) {
            EmptyDelegateState(Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 80.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(task = task, onDelete = { viewModel.delete(task.id) })
                }
            }
        }
    }

    if (showAdd) {
        AddTaskDialog(
            onDismiss = { showAdd = false },
            onDelegate = { label, prompt ->
                viewModel.delegate(label, prompt)
                showAdd = false
            },
        )
    }
}

@Composable
private fun TaskCard(task: AgentTask, onDelete: () -> Unit) {
    val statusColor = when (task.status) {
        AgentTaskStatus.QUEUED    -> MaterialTheme.colorScheme.onSurfaceVariant
        AgentTaskStatus.RUNNING   -> MaterialTheme.colorScheme.primary
        AgentTaskStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
        AgentTaskStatus.FAILED    -> MaterialTheme.colorScheme.error
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task.label, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Badge(containerColor = statusColor.copy(alpha = 0.15f)) {
                    Text(task.status.label, color = statusColor,
                        style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
            Text(task.prompt, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            task.result?.let { result ->
                HorizontalDivider()
                Text(result, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Text(
                SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(task.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun AddTaskDialog(onDismiss: () -> Unit, onDelegate: (String, String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delegate Task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it },
                    label = { Text("Task name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = prompt, onValueChange = { prompt = it },
                    label = { Text("Prompt / instructions") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                    maxLines = 5)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDelegate(label.ifBlank { "Task" }, prompt) },
                enabled = prompt.isNotBlank(),
            ) {
                Icon(Icons.Outlined.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Delegate")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyDelegateState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Send, contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        Spacer(Modifier.height(16.dp))
        Text("No delegated tasks", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text("Tap + to fire off a background task. Hermes will run it and report back.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
