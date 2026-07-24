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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.data.settings.UserSettings
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
                    "LobeChat, scripts) can use Jeeves as a backend.",
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
                                android.content.ClipData.newPlainText("Jeeves API key", settings.apiServerKey),
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
