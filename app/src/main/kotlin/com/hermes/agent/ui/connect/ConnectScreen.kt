package com.hermes.agent.ui.connect

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.domain.model.Connector
import com.hermes.agent.domain.model.ConnectorType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(viewModel: ConnectViewModel = hiltViewModel()) {
    val connectors by viewModel.connectors.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "Add connector")
            }
        },
    ) { padding ->
        if (connectors.isEmpty()) {
            EmptyConnectState(Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 80.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(connectors, key = { it.id }) { connector ->
                    ConnectorCard(
                        connector = connector,
                        onToggle = { viewModel.toggle(connector.id) },
                        onDelete = { viewModel.delete(connector.id) },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddConnectorDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, type, config ->
                viewModel.add(name, type, config)
                showAdd = false
            },
        )
    }
}

@Composable
private fun ConnectorCard(
    connector: Connector,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.Link,
                contentDescription = null,
                tint = if (connector.isEnabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(connector.name, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(connector.type.displayName, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                connector.lastUsedAt?.let { ts ->
                    Text(
                        "Last used: ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ts))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = connector.isEnabled, onCheckedChange = { onToggle() })
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddConnectorDialog(
    onDismiss: () -> Unit,
    onAdd: (String, ConnectorType, Map<String, String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ConnectorType.WEBHOOK) }
    var expanded by remember { mutableStateOf(false) }
    // config fields per type
    var url by remember { mutableStateOf("") }
    var botToken by remember { mutableStateOf("") }
    var chatId by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Integration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())

                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = type.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        ConnectorType.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(t.displayName) }, onClick = {
                                type = t; expanded = false
                            })
                        }
                    }
                }

                when (type) {
                    ConnectorType.WEBHOOK -> {
                        OutlinedTextField(value = url, onValueChange = { url = it },
                            label = { Text("Webhook URL") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                    }
                    ConnectorType.TELEGRAM -> {
                        OutlinedTextField(value = botToken, onValueChange = { botToken = it },
                            label = { Text("Bot Token") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = chatId, onValueChange = { chatId = it },
                            label = { Text("Chat ID") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                    }
                    ConnectorType.DISCORD -> {
                        OutlinedTextField(value = url, onValueChange = { url = it },
                            label = { Text("Webhook URL") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                    }
                    ConnectorType.SIGNAL -> {
                        OutlinedTextField(value = url, onValueChange = { url = it },
                            label = { Text("signal-cli REST API URL") },
                            placeholder = { Text("http://localhost:8080") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = chatId, onValueChange = { chatId = it },
                            label = { Text("Recipient (phone number)") },
                            placeholder = { Text("+15551234567") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    ConnectorType.WHATSAPP -> {
                        OutlinedTextField(value = chatId, onValueChange = { chatId = it },
                            label = { Text("Phone Number ID") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = botToken, onValueChange = { botToken = it },
                            label = { Text("Access Token") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = url, onValueChange = { url = it },
                            label = { Text("Recipient number") },
                            placeholder = { Text("+15551234567") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val config = when (type) {
                        ConnectorType.WEBHOOK  -> mapOf("url" to url)
                        ConnectorType.TELEGRAM -> mapOf("botToken" to botToken, "chatId" to chatId)
                        ConnectorType.DISCORD  -> mapOf("url" to url)
                        ConnectorType.SIGNAL   -> mapOf("url" to url, "recipient" to chatId)
                        ConnectorType.WHATSAPP -> mapOf("phoneNumberId" to chatId, "accessToken" to botToken, "recipient" to url)
                    }
                    onAdd(name.ifBlank { type.displayName }, type, config)
                },
                enabled = name.isNotBlank() || url.isNotBlank() || botToken.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyConnectState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Link, contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        Spacer(Modifier.height(16.dp))
        Text("No integrations yet", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text("Tap + to connect Telegram, Discord, Signal, WhatsApp, or a custom webhook.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
