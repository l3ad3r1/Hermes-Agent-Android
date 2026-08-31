package com.hermes.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.domain.mcp.McpServerConfig
import com.hermes.agent.domain.mcp.McpTransportType
import com.hermes.agent.domain.settings.UserSettings
import com.hermes.agent.service.ApiServerController
import com.hermes.agent.ui.components.DestructiveActionDialog
import com.hermes.agent.ui.theme.hermesFieldColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connections") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(text = "Local API server")
            ApiServerSection(
                settings = settings,
                onToggle = { enabled ->
                    viewModel.setApiServerEnabled(enabled)
                    if (enabled) ApiServerController.start(context) else ApiServerController.stop(context)
                },
                onAllowLan = viewModel::setApiServerAllowLan,
                onRegenerateKey = viewModel::regenerateApiServerKey,
            )

            SectionHeader(text = "Remote shell")
            RemoteShellSection(
                settings = settings,
                onHost = viewModel::setSshHost,
                onPort = viewModel::setSshPort,
                onUser = viewModel::setSshUser,
                onPassword = viewModel::setSshPassword,
            )

            SectionHeader(text = "Home Assistant")
            HomeAssistantSection(
                settings = settings,
                onUrl = viewModel::setHomeAssistantUrl,
                onToken = viewModel::setHomeAssistantToken,
                onTestConnection = viewModel::testHomeAssistantConnection,
            )

            SectionHeader(text = "MCP servers")
            McpServersSection()
        }
    }
}

@Composable
private fun ApiServerSection(
    settings: UserSettings,
    onToggle: (Boolean) -> Unit,
    onAllowLan: (Boolean) -> Unit,
    onRegenerateKey: () -> Unit,
) {
    val status by ApiServerController.status.collectAsStateWithLifecycle()
    val clipboard = LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)
    var tokenVisible by remember { mutableStateOf(false) }
    var confirmRegeneration by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ToggleRow(
                title = "Run local API server",
                subtitle = "Expose an OpenAI-compatible endpoint so other apps (Open WebUI, " +
                    "LobeChat, scripts) can use Hermes as a backend.",
                checked = settings.apiServerEnabled,
                onCheckedChange = onToggle,
            )

            if (settings.apiServerEnabled) {
                HorizontalDivider()

                val reachable = if (status.running) status.baseUrl
                else "http://${if (settings.apiServerAllowLan) "0.0.0.0" else "127.0.0.1"}:${settings.apiServerPort}/v1"
                InfoRow(title = "Status", value = if (status.running) "Running" else "Starting…")
                InfoRow(title = "Endpoint", value = reachable)
                status.error?.let {
                    Text(
                        "Error: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                OutlinedTextField(
                    value = settings.apiServerKey,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Bearer token") },
                    supportingText = { Text("Send as: Authorization: Bearer <token>") },
                    singleLine = true,
                    visualTransformation = if (tokenVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    colors = hermesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { tokenVisible = !tokenVisible },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (tokenVisible) "Hide token" else "Reveal token")
                    }
                    OutlinedButton(
                        onClick = {
                            clipboard?.setPrimaryClip(
                                android.content.ClipData.newPlainText("Hermes API key", settings.apiServerKey),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Copy token") }
                }
                OutlinedButton(
                    onClick = { confirmRegeneration = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Regenerate token") }

                HorizontalDivider()
                ToggleRow(
                    title = "Allow LAN access",
                    subtitle = "Off: reachable only from this device (127.0.0.1). " +
                        "On: reachable from other devices on your Wi-Fi — keep the token secret.",
                    checked = settings.apiServerAllowLan,
                    onCheckedChange = onAllowLan,
                )
                Text(
                    "Changing LAN or port takes effect the next time you toggle the server off and on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmRegeneration) {
        DestructiveActionDialog(
            title = "Regenerate bearer token?",
            message = "Apps using the current token will lose access until you update their connection settings.",
            confirmLabel = "Regenerate token",
            onConfirm = {
                onRegenerateKey()
                tokenVisible = false
                confirmRegeneration = false
            },
            onDismiss = { confirmRegeneration = false },
        )
    }
}

@Composable
private fun RemoteShellSection(
    settings: UserSettings,
    onHost: (String) -> Unit,
    onPort: (Int) -> Unit,
    onUser: (String) -> Unit,
    onPassword: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Let the shell tool run commands on a remote host over SSH " +
                    "(target='remote'). Through SSH you also reach Docker on that host " +
                    "(docker exec …). Leave the host blank to keep the shell on-device only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var host by remember(settings.sshHost) { mutableStateOf(settings.sshHost) }
            OutlinedTextField(
                value = host,
                onValueChange = { host = it; onHost(it) },
                label = { Text("Host") },
                placeholder = { Text("192.168.1.10 or example.com") },
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                var user by remember(settings.sshUser) { mutableStateOf(settings.sshUser) }
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it; onUser(it) },
                    label = { Text("User") },
                    singleLine = true,
                    colors = hermesFieldColors(),
                    modifier = Modifier.weight(1f),
                )
                var portText by remember(settings.sshPort) { mutableStateOf(settings.sshPort.toString()) }
                OutlinedTextField(
                    value = portText,
                    onValueChange = {
                        portText = it.filter(Char::isDigit).take(5)
                        portText.toIntOrNull()?.let(onPort)
                    },
                    label = { Text("Port") },
                    singleLine = true,
                    colors = hermesFieldColors(),
                    modifier = Modifier.width(96.dp),
                )
            }

            var password by remember(settings.sshPassword) { mutableStateOf(settings.sshPassword) }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; onPassword(it) },
                label = { Text("Password") },
                supportingText = { Text("Stored on-device. Host-key checking is disabled (trusted networks only).") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HomeAssistantSection(
    settings: UserSettings,
    onUrl: (String) -> Unit,
    onToken: (String) -> Unit,
    onTestConnection: ((Boolean, String) -> Unit) -> Unit,
) {
    var tokenVisible by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Control lights, switches, climates, and scenes via your local or remote Home Assistant instance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var url by remember(settings.homeAssistantUrl) { mutableStateOf(settings.homeAssistantUrl) }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; onUrl(it) },
                label = { Text("Base URL") },
                placeholder = { Text("http://homeassistant.local:8123") },
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            var token by remember(settings.homeAssistantToken) { mutableStateOf(settings.homeAssistantToken) }
            OutlinedTextField(
                value = token,
                onValueChange = { token = it; onToken(it) },
                label = { Text("Long-lived access token") },
                supportingText = { Text("Create under Profile -> Security -> Long-Lived Access Tokens in Home Assistant.") },
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { tokenVisible = !tokenVisible },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (tokenVisible) "Hide token" else "Reveal token")
                }
                OutlinedButton(
                    onClick = {
                        testing = true
                        testResult = null
                        onTestConnection { success, message ->
                            testing = false
                            testResult = success to message
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (testing) "Testing…" else "Test connection")
                }
            }

            testResult?.let { (success, message) ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}


/**
 * Registry for Model Context Protocol servers.
 *
 * This is the only place a server can be added, so without it the `mcp_servers`
 * table stayed empty for every install: no MCP tool was ever registered, and the
 * tool-search bridge never had anything to defer. Adding a server syncs it
 * immediately so a bad URL is visible here rather than as silence in chat.
 */
@Composable
private fun McpServersSection(
    viewModel: McpSettingsViewModel = hiltViewModel(),
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val toolCounts by viewModel.toolCounts.collectAsStateWithLifecycle()
    val busyServerId by viewModel.busyServerId.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<McpServerConfig?>(null) }
    var banner by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Connect Model Context Protocol servers over HTTP or SSE. Their tools become " +
                    "available to the agent, namespaced per server and confirmation-gated before " +
                    "they run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (servers.isEmpty()) {
                Text(
                    "No servers configured. The agent has no MCP tools until you add one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            servers.forEach { server ->
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(server.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            server.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val count = toolCounts[server.id] ?: 0
                        Text(
                            "${server.transport.name} - $count tool(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        server.lastError?.let { err ->
                            Text(
                                "Last error: $err",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Switch(
                        checked = server.enabled,
                        onCheckedChange = { viewModel.setEnabled(server.id, it) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            banner = null
                            viewModel.sync(server.id) { ok, message -> banner = ok to message }
                        },
                        enabled = server.enabled && busyServerId == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (busyServerId == server.id) "Syncing..." else "Sync tools")
                    }
                    OutlinedButton(
                        onClick = { pendingDelete = server },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Remove")
                    }
                }
            }

            banner?.let { (ok, message) ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider()
            OutlinedButton(
                onClick = { banner = null; showAddDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add MCP server")
            }
        }
    }

    if (showAddDialog) {
        AddMcpServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url, transport, headerName, headerValue ->
                viewModel.addServer(name, url, transport, headerName, headerValue) { ok, message ->
                    banner = ok to message
                    if (ok) showAddDialog = false
                }
            },
        )
    }

    pendingDelete?.let { server ->
        DestructiveActionDialog(
            title = "Remove ${server.name}?",
            message = "Its tools are unregistered and its cached catalogue is deleted. The server " +
                "itself is not affected.",
            confirmLabel = "Remove",
            onConfirm = {
                viewModel.deleteServer(server.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun AddMcpServerDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, McpTransportType, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf(McpTransportType.HTTP) }
    var headerName by remember { mutableStateOf("") }
    var headerValue by remember { mutableStateOf("") }
    var headerVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add MCP server") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("context7") },
                    singleLine = true,
                    colors = hermesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    placeholder = { Text("https://example.com/mcp") },
                    supportingText = {
                        Text("HTTP or SSE endpoint. Servers that run as a local process are not " +
                            "supported in the app sandbox - run them under Termux and point here " +
                            "at their localhost port.")
                    },
                    singleLine = true,
                    colors = hermesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    McpTransportType.entries.forEach { option ->
                        FilterChip(
                            selected = transport == option,
                            onClick = { transport = option },
                            label = { Text(option.name) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                OutlinedTextField(
                    value = headerName,
                    onValueChange = { headerName = it },
                    label = { Text("Auth header name (optional)") },
                    placeholder = { Text("Authorization") },
                    singleLine = true,
                    colors = hermesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = headerValue,
                    onValueChange = { headerValue = it },
                    label = { Text("Auth header value (optional)") },
                    visualTransformation = if (headerVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    singleLine = true,
                    colors = hermesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { headerVisible = !headerVisible }) {
                    Text(if (headerVisible) "Hide value" else "Reveal value")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, url, transport, headerName, headerValue) }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
