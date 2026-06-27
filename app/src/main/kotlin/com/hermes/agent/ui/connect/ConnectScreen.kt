package com.hermes.agent.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.domain.model.Connector
import com.hermes.agent.domain.model.ConnectorType
import com.hermes.agent.ui.theme.GeistMono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(viewModel: ConnectViewModel = hiltViewModel()) {
    val connectors by viewModel.connectors.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAdd = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Add connector")
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 18.dp, end = 18.dp,
                top = padding.calculateTopPadding() + 10.dp,
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Messaging",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "One agent, one memory, every surface. Link a platform and Hermes shows up there.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (connectors.isEmpty()) {
                item { EmptyConnectState() }
            } else {
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
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(scheme.surface)
            .border(1.dp, scheme.outline.copy(alpha = 0.25f), MaterialTheme.shapes.medium)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        // Letter glyph tile
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(scheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                connector.name.firstOrNull()?.uppercase() ?: "?",
                fontFamily = GeistMono,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = if (connector.isEnabled) scheme.primary else scheme.outline,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                connector.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                connector.type.displayName,
                fontFamily = GeistMono,
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (connector.isEnabled) "Connected" else "Connect",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (connector.isEnabled) scheme.tertiary else scheme.primary,
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Delete",
                tint = scheme.outline,
            )
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
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(scheme.surface)
            .border(1.dp, scheme.outline.copy(alpha = 0.25f), MaterialTheme.shapes.medium)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Link, contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = scheme.outline)
        Spacer(Modifier.height(14.dp))
        Text("No integrations yet", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text("Tap + to connect Telegram, Discord, Signal, WhatsApp, or a custom webhook.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
