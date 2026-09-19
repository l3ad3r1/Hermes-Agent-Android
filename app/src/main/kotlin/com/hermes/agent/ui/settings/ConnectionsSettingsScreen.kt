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
import androidx.compose.material3.Button
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.hermes.agent.data.remote.TailnetStatus
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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
                    viewModel.setApiServerEnabled(enabled) {
                        if (enabled) ApiServerController.start(context) else ApiServerController.stop(context)
                    }
                },
                onAllowLan = viewModel::setApiServerAllowLan,
                onRegenerateKey = {
                    viewModel.regenerateApiServerKey {
                        if (ApiServerController.status.value.running) ApiServerController.restart(context)
                    }
                },
            )

            SectionHeader(text = "Remote shell")
            RemoteShellSection(
                settings = settings,
                onHost = viewModel::setSshHost,
                onPort = viewModel::setSshPort,
                onUser = viewModel::setSshUser,
                onPassword = viewModel::setSshPassword,
                onHostFingerprint = viewModel::setSshHostFingerprint,
            )

            SectionHeader(text = "Home Assistant")
            HomeAssistantSection(
                settings = settings,
                onUrl = viewModel::setHomeAssistantUrl,
                onToken = viewModel::setHomeAssistantToken,
                onDashboardPath = viewModel::setHomeAssistantDashboardPath,
                onDashboardEnabled = viewModel::setHomeAssistantDashboardEnabled,
                onTestConnection = viewModel::testHomeAssistantConnection,
            )

            SectionHeader(text = "Tailnet (experimental)")
            TailnetSection(viewModel = viewModel)

            SectionHeader(text = "Remote gateway")
            RemoteGatewaySection(
                settings = settings,
                onToggle = viewModel::setRemoteGatewayEnabled,
                onUrl = viewModel::setRemoteGatewayUrl,
                onApiKey = viewModel::setRemoteGatewayApiKey,
                onTestConnection = viewModel::testRemoteGatewayConnection,
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
                    trailingIcon = { RevealToggle(tokenVisible) { tokenVisible = !tokenVisible } },
                    colors = hermesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        clipboard?.setPrimaryClip(
                            android.content.ClipData.newPlainText("Hermes API key", settings.apiServerKey),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Copy token") }
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
                InfoNote(
                    "When changes apply",
                    "Changing LAN or port takes effect the next time you toggle the server off and on.",
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
    onHostFingerprint: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoNote(
                "About the remote shell",
                "Let the shell tool run commands on a remote host over SSH " +
                    "(target='remote'). Through SSH you also reach Docker on that host " +
                    "(docker exec …). Leave the host blank to keep the shell on-device only.",
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
                supportingText = { Text("Stored encrypted on-device. Authentication also requires the host fingerprint below.") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            var hostFingerprint by remember(settings.sshHostFingerprint) {
                mutableStateOf(settings.sshHostFingerprint)
            }
            OutlinedTextField(
                value = hostFingerprint,
                onValueChange = { hostFingerprint = it; onHostFingerprint(it) },
                label = { Text("Host key fingerprint") },
                placeholder = { Text("SHA256:… or aa:bb:…") },
                supportingText = { Text("Verify this fingerprint out of band before saving it.") },
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
    onDashboardPath: (String) -> Unit,
    onDashboardEnabled: (Boolean) -> Unit,
    onTestConnection: ((Boolean, String) -> Unit) -> Unit,
) {
    var tokenVisible by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoNote(
                "About Home Assistant control",
                "Control lights, switches, climates, and scenes via your local or remote Home Assistant instance.",
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
                trailingIcon = { RevealToggle(tokenVisible) { tokenVisible = !tokenVisible } },
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

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
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (testing) "Testing…" else "Test connection")
            }

            testResult?.let { (success, message) ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            var dashboard by remember(settings.homeAssistantDashboardPath) {
                mutableStateOf(settings.homeAssistantDashboardPath)
            }
            OutlinedTextField(
                value = dashboard,
                onValueChange = { dashboard = it; onDashboardPath(it) },
                label = { Text("Dashboard path (optional)") },
                placeholder = { Text("lovelace/0") },
                supportingText = { Text("Relative to the base URL. Leave blank for the default dashboard.") },
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow(
                title = "Show on Home screen",
                subtitle = "Add a Home Assistant tile to the Home dashboard that opens this dashboard in-app.",
                checked = settings.homeAssistantDashboardEnabled,
                onCheckedChange = onDashboardEnabled,
            )
        }
    }
}

/**
 * SPIKE: the in-app Tailscale node. With it running, the gateway URL below is reached over
 * the tailnet with no Tailscale app installed and no VPN permission.
 */
@Composable
private fun TailnetSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<TailnetStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf("") }
    // Sign-in link already opened for the user's own Start-node tap; keyed by URL so a new
    // link (a fresh login attempt) is opened again, but the same link is not reopened on every poll.
    var startedByUser by remember { mutableStateOf(false) }
    var openedUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshTailnet { status = it }
            kotlinx.coroutines.delay(2000)
        }
    }

    val pendingUrl = status?.authUrl
    LaunchedEffect(pendingUrl, startedByUser) {
        if (startedByUser && pendingUrl != null && pendingUrl != openedUrl) {
            openedUrl = pendingUrl
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(pendingUrl))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val current = status
            Text(
                text = buildString {
                    append("State: ").append(current?.state ?: "…")
                    current?.hostname?.let { append("\nNode: ").append(it) }
                    current?.addresses?.takeIf { it.isNotEmpty() }?.let { append("\nAddress: ").append(it.joinToString(", ")) }
                    current?.error?.let { append("\nError: ").append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (current?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        busy = true
                        if (current?.running == true) {
                            viewModel.stopTailnet { status = it; busy = false }
                        } else {
                            startedByUser = true
                            openedUrl = null
                            viewModel.startTailnet { status = it; busy = false }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = SettingsButtonPadding,
                ) {
                    ButtonLabel(if (current?.running == true) "Stop node" else "Start node")
                }
                OutlinedButton(
                    onClick = { viewModel.tailnetLogs { logs = it.takeLast(2000) } },
                    modifier = Modifier.weight(1f),
                    contentPadding = SettingsButtonPadding,
                ) {
                    ButtonLabel("Show logs")
                }
            }

            // Sign-in is shown whenever the node needs it, including after an app restart with
            // the node left on. Tailscale's server takes several seconds to return the link, so
            // say so instead of showing nothing.
            if (current?.state == "NeedsLogin") {
                val url = current.authUrl
                if (url == null) {
                    Text(
                        "Getting your Tailscale sign-in link…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        "Sign in to finish connecting this device to your tailnet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Button(
                        onClick = {
                            openedUrl = url
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Sign in to Tailscale")
                    }
                    OutlinedButton(
                        onClick = {
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                ?.setPrimaryClip(android.content.ClipData.newPlainText("Tailscale sign-in link", url))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = SettingsButtonPadding,
                    ) {
                        ButtonLabel("Copy link")
                    }
                }
            }

            if (logs.isNotBlank()) {
                Text(logs, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            InfoNote(
                "About this node",
                "Experimental. The node runs inside this app only — no VPN permission, and " +
                    "nothing else on the phone is rerouted. It keeps its key in this app's storage, " +
                    "so signing in is a one-time step.",
            )
        }
    }
}

@Composable
private fun RemoteGatewaySection(
    settings: UserSettings,
    onToggle: (Boolean) -> Unit,
    onUrl: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onTestConnection: (String, String, (Boolean, String) -> Unit) -> Unit,
) {
    var keyVisible by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ToggleRow(
                title = "Use remote gateway",
                subtitle = "Delegate all agent execution to a PC Hermes gateway. " +
                    "The PC is the canonical conversation store; the phone is a chat + " +
                    "approval surface. Requires app restart to take effect.",
                checked = settings.remoteGatewayEnabled,
                onCheckedChange = onToggle,
            )

            // URL and key stay visible with thin-client mode off: the desktop_bots tool uses them too.
            HorizontalDivider()

            InfoNote(
                "Which address to use",
                "Point at your PC's Hermes gateway. The gateway runs " +
                    "`hermes gateway` and exposes an API server (default port 8642). " +
                    "Use a hostname the phone can resolve: an mDNS name " +
                    "(http://hermes-pc.local:8642) on your LAN, or a Tailscale " +
                    "MagicDNS name (http://mymachine.tailnet.ts.net:8642). " +
                    "Cleartext HTTP is allowed for these hosts; for a raw IP " +
                    "use HTTPS, Tailscale Serve, or a reverse proxy.",
            )

            // Seeded once, not re-keyed on the stored value: persisting per keystroke and
            // re-seeding from the settings flow reorders characters while typing. The settings
            // flow's first emission is the empty default, though, so adopt the stored URL when it
            // arrives and the field has not been edited — otherwise the blank seed was written
            // back over a saved URL.
            var url by rememberSaveable { mutableStateOf(settings.remoteGatewayUrl) }
            var urlEdited by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(settings.remoteGatewayUrl) {
                if (!urlEdited && url != settings.remoteGatewayUrl) url = settings.remoteGatewayUrl
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; urlEdited = true },
                label = { Text("Gateway URL") },
                placeholder = { Text("http://hermes-pc.local:8642") },
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focusState ->
                    if (!focusState.isFocused && url != settings.remoteGatewayUrl) onUrl(url)
                },
            )

            var key by remember(settings.remoteGatewayApiKey) {
                mutableStateOf(settings.remoteGatewayApiKey)
            }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("Gateway API key") },
                supportingText = { Text("The PC's API_SERVER_KEY (set in ~/.hermes/.env).") },
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { RevealToggle(keyVisible) { keyVisible = !keyVisible } },
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focusState ->
                    // Persist on focus loss, not per keystroke: the API key
                    // is encrypted with the hardware Keystore on every write,
                    // which is far too slow to run for each character typed.
                    if (!focusState.isFocused && key != settings.remoteGatewayApiKey) {
                        onApiKey(key)
                    }
                },
            )

            // Leaving the screen with a field still focused never reports focus loss, so what was
            // typed was silently dropped. Commit any pending edit on the way out.
            val latestUrl by rememberUpdatedState(url)
            val latestKey by rememberUpdatedState(key)
            val latestSettings by rememberUpdatedState(settings)
            DisposableEffect(Unit) {
                onDispose {
                    if (latestUrl != latestSettings.remoteGatewayUrl) onUrl(latestUrl)
                    if (latestKey != latestSettings.remoteGatewayApiKey) onApiKey(latestKey)
                }
            }

            OutlinedButton(
                onClick = {
                    // The field saves on focus loss; save explicitly too
                    // in case the button press consumed the focus event. The URL as
                    // well: this used to save only the key, so a test could report
                    // "Connected" for a URL that was never stored.
                    if (url != settings.remoteGatewayUrl) onUrl(url)
                    if (key != settings.remoteGatewayApiKey) onApiKey(key)
                    testing = true
                    testResult = null
                    onTestConnection(url, key) { success, message ->
                        testing = false
                        testResult = success to message
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (testing) "Testing…" else "Test connection")
            }

            testResult?.let { (success, message) ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider()
            InfoNote(
                "What the remote gateway does",
                "When enabled, conversations on this phone map to PC gateway " +
                    "sessions. A turn sent from the phone appears on the PC, and " +
                    "vice versa. Tool approvals are forwarded to the phone's " +
                    "existing approval dialog.",
            )
            HorizontalDivider()
            InfoNote(
                "Security",
                "The phone inherits the full authority of the PC " +
                    "gateway — terminal, file operations, everything the agent " +
                    "can do. The API key is stored in the Android Keystore and " +
                    "never leaves the device, but anyone with the key can drive " +
                    "the agent. For a scoped connection, configure a dedicated " +
                    "gateway profile (e.g. /p/phone/) with a restricted toolset " +
                    "and point this URL at that profile prefix. The gateway is " +
                    "the enforcement point — the phone cannot self-limit tools " +
                    "that the PC has already started.",
                textColor = MaterialTheme.colorScheme.error,
            )
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
            InfoNote(
                "About MCP servers",
                "Connect Model Context Protocol servers over HTTP or SSE. Their tools become " +
                    "available to the agent, namespaced per server and confirmation-gated before " +
                    "they run.",
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            banner = null
                            viewModel.sync(server.id) { ok, message -> banner = ok to message }
                        },
                        enabled = server.enabled && busyServerId == null,
                        modifier = Modifier.weight(1f),
                        contentPadding = SettingsButtonPadding,
                    ) {
                        ButtonLabel(if (busyServerId == server.id) "Syncing..." else "Sync tools")
                    }
                    OutlinedButton(
                        onClick = { pendingDelete = server },
                        modifier = Modifier.weight(1f),
                        contentPadding = SettingsButtonPadding,
                    ) {
                        ButtonLabel("Remove")
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
                    singleLine = true,
                    colors = hermesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                InfoNote(
                    "About the URL",
                    "HTTP or SSE endpoint. Servers that run as a local process are not " +
                        "supported in the app sandbox - run them under Termux and point here " +
                        "at their localhost port.",
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
