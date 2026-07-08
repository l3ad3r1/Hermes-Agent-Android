package com.hermes.agent.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.hermes.agent.ui.components.SlimTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.BuildConfig
import com.hermes.agent.R
import com.hermes.agent.data.settings.UserSettings
import com.hermes.agent.service.ApiServerController
import com.hermes.agent.ui.theme.AppTheme
import com.hermes.agent.ui.theme.hermesFieldColors
import com.hermes.agent.ui.theme.hermesSwitchColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigate: (String) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            SlimTopBar(title = stringResource(R.string.nav_settings))
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // --- Cloud LLM (the core setup — first) ---
            SectionHeader(text = stringResource(R.string.settings_section_cloud))
            CloudSection(settings = settings, viewModel = viewModel)

            // --- Chat ---
            SectionHeader(text = "Chat")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ToggleRow(
                        title = "Show tool call details",
                        subtitle = "See what the agent does mid-reply (web search, calendar, etc.) " +
                            "instead of just the final answer",
                        checked = settings.showToolCalls,
                        onCheckedChange = viewModel::setShowToolCalls,
                    )
                }
            }

            // --- Appearance ---
            SectionHeader(text = "Appearance")
            ThemePicker(
                currentTheme = settings.appTheme,
                onThemeSelected = viewModel::setAppTheme,
            )

            // --- Features (navigate to sub-screens) ---
            SectionHeader(text = "Features")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    NavRow(
                        icon = Icons.Outlined.Psychology,
                        title = "Memory",
                        subtitle = "View and manage agent memories",
                        onClick = { onNavigate("memory") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.AutoAwesome,
                        title = "Learning",
                        subtitle = "Facts learned, your profile, and auto-created skills",
                        onClick = { onNavigate("learning") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.Description,
                        title = "Artifacts",
                        subtitle = "Documents and files indexed for retrieval",
                        onClick = { onNavigate("documents") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.Stars,
                        title = "Skills & Tools",
                        subtitle = "Browse and manage the agent's skills and tools",
                        onClick = { onNavigate("skills") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.Science,
                        title = "Refine skills",
                        subtitle = "Improve a skill from how it was actually used",
                        onClick = { onNavigate("refine_skills") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.Link,
                        title = "Messaging",
                        subtitle = "Configure Telegram, Discord, Signal, WhatsApp",
                        onClick = { onNavigate("connect") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.Schedule,
                        title = "CRON",
                        subtitle = "Manage cron jobs and recurring agent tasks",
                        onClick = { onNavigate("schedule") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.AutoMirrored.Outlined.Send,
                        title = "Delegate",
                        subtitle = "Run background agent tasks and see their results",
                        onClick = { onNavigate("delegate") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.Science,
                        title = "Experiment",
                        subtitle = "Compare two models side-by-side on the same prompt",
                        onClick = { onNavigate("experiment") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.Article,
                        title = "Logs",
                        subtitle = "View, copy, or share app logs for troubleshooting",
                        onClick = { onNavigate("logs") },
                    )
                }
            }

            // --- Local API server ---
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

            // --- Remote shell (SSH) ---
            SectionHeader(text = "Remote shell")
            RemoteShellSection(
                settings = settings,
                onHost = viewModel::setSshHost,
                onPort = viewModel::setSshPort,
                onUser = viewModel::setSshUser,
                onPassword = viewModel::setSshPassword,
            )

            // --- Backup & Restore ---
            SectionHeader(text = "Backup & Restore")
            BackupSection(
                githubPat = settings.githubPat,
                gistId = settings.gistId,
                lastBackupTimestamp = settings.lastBackupTimestamp,
                state = backupState,
                onPatChange = viewModel::setGithubPat,
                onGistIdChange = viewModel::setGistId,
                onBackup = viewModel::backupNow,
                onRestore = viewModel::restoreBackup,
                onDismiss = viewModel::dismissBackupState,
                onClearGistId = viewModel::clearGistId,
            )

            // --- Session export (offline self-evolution) ---
            SectionHeader(text = "Self-Evolution")
            ExportSection(
                state = exportState,
                onExport = viewModel::exportSessions,
                onShare = { zip ->
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        zip,
                    )
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(share, "Share session export"))
                },
                onDismiss = viewModel::dismissExportState,
            )

            // --- OTA Update ---
            SectionHeader(text = "Updates")
            UpdateSection(
                state = updateState,
                canInstall = viewModel.canInstallPackages(),
                onCheck = viewModel::checkForUpdate,
                onDownload = viewModel::downloadAndInstall,
                onManagePermission = viewModel::promptInstallPermission,
                onOpenUrl = { url ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    viewModel.dismissUpdateState()
                },
                onDismiss = viewModel::dismissUpdateState,
            )

            // --- Security ---
            SectionHeader(text = stringResource(R.string.settings_section_security))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow(
                        title = stringResource(R.string.settings_knox_status),
                        value = if (viewModel.isKnoxAvailable) "Available" else "Not available on this device",
                    )
                    HorizontalDivider()
                    InfoRow(
                        title = stringResource(R.string.settings_keystore_status),
                        value = "Hardware-backed (Android Keystore)",
                    )
                }
            }

            // --- About ---
            SectionHeader(text = stringResource(R.string.settings_section_about))
            SecurityAuditPanel()
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoRow(title = "Application", value = "Hermes Agent")
                    InfoRow(
                        title = stringResource(R.string.settings_app_version),
                        value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    )
                    InfoRow(title = "Build type", value = BuildConfig.BUILD_TYPE)
                }
            }
        }
    }
}

@Composable
private fun CloudSection(
    settings: com.hermes.agent.data.settings.UserSettings,
    viewModel: SettingsViewModel,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleRow(
                title = stringResource(R.string.settings_cloud_enabled),
                subtitle = stringResource(R.string.settings_cloud_enabled_subtitle),
                checked = settings.cloudEnabled,
                onCheckedChange = viewModel::setCloudEnabled,
            )
            HorizontalDivider()

            // Primary provider (complex / general tasks).
            Text(
                "Primary provider — general tasks",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var apiKey by remember(settings.cloudApiKey) { mutableStateOf(settings.cloudApiKey) }
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    viewModel.setCloudApiKey(it)
                },
                label = { Text(stringResource(R.string.settings_cloud_api_key)) },
                supportingText = {
                    Text(stringResource(R.string.settings_cloud_api_key_subtitle))
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            var baseUrl by remember(settings.cloudBaseUrl) { mutableStateOf(settings.cloudBaseUrl) }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    viewModel.setCloudBaseUrl(it)
                },
                label = { Text(stringResource(R.string.settings_cloud_base_url)) },
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            var model by remember(settings.cloudModel) { mutableStateOf(settings.cloudModel) }
            OutlinedTextField(
                value = model,
                onValueChange = {
                    model = it
                    viewModel.setCloudModel(it)
                },
                label = { Text(stringResource(R.string.settings_cloud_model)) },
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()

            // Specialist provider (simpler / specialised tasks). The router
            // sends lighter requests here. URL + key are optional — leave
            // blank to reuse the primary provider's endpoint and key.
            Text(
                "Specialist provider — specialised tasks",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var specialisedModel by remember(settings.auxModel) { mutableStateOf(settings.auxModel) }
            OutlinedTextField(
                value = specialisedModel,
                onValueChange = {
                    specialisedModel = it
                    viewModel.setAuxModel(it)
                },
                label = { Text(stringResource(R.string.settings_specialised_model)) },
                supportingText = { Text(stringResource(R.string.settings_specialised_model_hint)) },
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            var auxBaseUrl by remember(settings.auxBaseUrl) { mutableStateOf(settings.auxBaseUrl) }
            OutlinedTextField(
                value = auxBaseUrl,
                onValueChange = {
                    auxBaseUrl = it
                    viewModel.setAuxBaseUrl(it)
                },
                label = { Text("Specialist base URL (optional)") },
                supportingText = { Text("Leave blank to use the primary provider's endpoint.") },
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            var auxApiKey by remember(settings.auxApiKey) { mutableStateOf(settings.auxApiKey) }
            OutlinedTextField(
                value = auxApiKey,
                onValueChange = {
                    auxApiKey = it
                    viewModel.setAuxApiKey(it)
                },
                label = { Text("Specialist API key (optional)") },
                supportingText = { Text("Leave blank to use the primary provider's key.") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
    }
}

private data class ThemeOption(
    val name: String,
    val label: String,
    val bg: Color,
    val fg: Color,
    val accent: Color,
)

private val themeOptions = listOf(
    ThemeOption("MIDNIGHT",    "Midnight",     Color(0xFF0A0A0F), Color(0xFFF3F3F6), Color(0xFF5B73FF)),
    ThemeOption("PAPER",       "Paper",        Color(0xFFF3F2EE), Color(0xFF0D0D12), Color(0xFF0000F2)),
    ThemeOption("HERMES_BLUE", "Hermes Blue",  Color(0xFF3300FF), Color(0xFFFFFFFF), Color(0xFF2200CC)),
)

@Composable
private fun ThemePicker(
    currentTheme: String,
    onThemeSelected: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "App Theme", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                themeOptions.forEach { option ->
                    ThemeSwatch(
                        option = option,
                        selected = currentTheme == option.name,
                        onClick = { onThemeSelected(option.name) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeSwatch(
    option: ThemeOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(2.dp, borderColor, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
        ) {
            Surface(
                modifier = Modifier.matchParentSize(),
                color = option.bg,
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(0.7f).height(8.dp),
                        color = option.fg,
                        shape = RoundedCornerShape(4.dp),
                        content = {},
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(0.5f).height(6.dp),
                        color = option.fg.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp),
                        content = {},
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(20.dp),
                        color = option.accent,
                        shape = RoundedCornerShape(4.dp),
                        content = {},
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = option.fg,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp),
                )
            }
        }
        Text(
            text = option.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiServerSection(
    settings: UserSettings,
    onToggle: (Boolean) -> Unit,
    onAllowLan: (Boolean) -> Unit,
    onRegenerateKey: () -> Unit,
) {
    val status by ApiServerController.status.collectAsStateWithLifecycle()
    val clipboard = LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)

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

                // API key (read-only display + copy + regenerate).
                OutlinedTextField(
                    value = settings.apiServerKey,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Bearer token") },
                    supportingText = { Text("Send as: Authorization: Bearer <token>") },
                    singleLine = true,
                    colors = hermesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        clipboard?.setPrimaryClip(
                            android.content.ClipData.newPlainText("Hermes API key", settings.apiServerKey),
                        )
                    }) { Text("Copy token") }
                    OutlinedButton(onClick = onRegenerateKey) { Text("Regenerate") }
                }

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
}

@OptIn(ExperimentalMaterial3Api::class)
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
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = hermesSwitchColors())
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun UpdateSection(
    state: UpdateUiState,
    canInstall: Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onManagePermission: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text("Over-the-air Updates", style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                "Current version: ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (state) {
                is UpdateUiState.Idle, is UpdateUiState.Error -> {
                    if (state is UpdateUiState.Error) {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    FilledTonalButton(
                        onClick = onCheck,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Check for updates")
                    }
                }
                is UpdateUiState.Checking -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Checking…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is UpdateUiState.UpToDate -> {
                    Text(
                        "You're on the latest version.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text("Dismiss")
                    }
                }
                is UpdateUiState.UpdateAvailable -> {
                    Text(
                        "Hermes ${state.version} is available!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (state.apkUrl.isNotBlank()) {
                        if (!canInstall) {
                            Text(
                                "Allow \"install unknown apps\" for Hermes so it can " +
                                    "install the update directly.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = onManagePermission, modifier = Modifier.fillMaxWidth()) {
                                Text("Allow installs")
                            }
                        }
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Download & install")
                        }
                    } else {
                        // No APK attached to this release — fall back to the release page.
                        Button(
                            onClick = { onOpenUrl(state.releaseUrl) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("View release")
                        }
                    }
                }
                is UpdateUiState.Downloading -> {
                    Text(
                        "Downloading Hermes ${state.version}… ${state.percent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportSection(
    state: ExportUiState,
    onExport: () -> Unit,
    onShare: (java.io.File) -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Science,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text("Export sessions for evolution", style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                "Exports your conversations as a JSON archive for the offline " +
                    "hermes-agent-self-evolution tool. Unzip into ~/.hermes/sessions/ " +
                    "on your computer, then run the evolver with --eval-source sessiondb. " +
                    "The archive contains raw chat text — treat it as sensitive.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (state) {
                is ExportUiState.InProgress -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Exporting…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is ExportUiState.Ready -> {
                    Text(
                        "Exported ${state.sessionCount} sessions (${state.messageCount} messages).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onShare(state.zipFile) }, modifier = Modifier.weight(1f)) {
                            Text("Share archive")
                        }
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                            Text("Done")
                        }
                    }
                }
                is ExportUiState.Error -> {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    FilledTonalButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                        Text("Retry export")
                    }
                }
                is ExportUiState.Idle -> {
                    FilledTonalButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                        Text("Export sessions")
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupSection(
    githubPat: String,
    gistId: String,
    lastBackupTimestamp: Long,
    state: BackupUiState,
    onPatChange: (String) -> Unit,
    onGistIdChange: (String) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
    onClearGistId: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Backup,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text("GitHub Gist Backup", style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                "Backs up Cloud LLM settings (including your API keys), memories, " +
                    "skills and cron jobs to a private GitHub Gist — keep the PAT and " +
                    "gist safe. To restore on a new install, paste the same PAT and " +
                    "Gist ID below, then tap Restore.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var pat by remember(githubPat) { mutableStateOf(githubPat) }
            OutlinedTextField(
                value = pat,
                onValueChange = {
                    pat = it
                    onPatChange(it)
                },
                label = { Text("GitHub Personal Access Token") },
                supportingText = {
                    Text(
                        "Classic PAT: github.com → Settings → Developer settings → " +
                            "Personal access tokens (classic) → gist scope.\n" +
                            "Fine-grained PAT: enable Gists → Read and write."
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            // Editable Gist ID — always shown so a fresh install can paste the ID
            // of an existing backup and restore from it. Filled automatically after
            // the first "Backup now".
            var gist by remember(gistId) { mutableStateOf(gistId) }
            OutlinedTextField(
                value = gist,
                onValueChange = {
                    gist = it
                    onGistIdChange(it)
                },
                label = { Text("Gist ID") },
                supportingText = {
                    Text(
                        "Auto-filled after your first backup. On a new install, paste " +
                            "the Gist ID from your previous device (the id in the gist URL)."
                    )
                },
                singleLine = true,
                trailingIcon = {
                    if (gist.isNotBlank()) {
                        Text(
                            "Clear",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .clickable {
                                    gist = ""
                                    onClearGistId()
                                }
                                .padding(horizontal = 12.dp),
                        )
                    }
                },
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            if (lastBackupTimestamp > 0L) {
                Text(
                    "Last backup: ${dateFmt.format(Date(lastBackupTimestamp))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (state) {
                is BackupUiState.InProgress -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Working…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is BackupUiState.Success -> {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text("Dismiss")
                    }
                }
                is BackupUiState.Error -> {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> Unit
            }

            if (state !is BackupUiState.InProgress && state !is BackupUiState.Success) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onBackup,
                        modifier = Modifier.weight(1f),
                        enabled = githubPat.isNotBlank(),
                    ) {
                        Text("Backup now")
                    }
                    OutlinedButton(
                        onClick = onRestore,
                        modifier = Modifier.weight(1f),
                        enabled = githubPat.isNotBlank() && gistId.isNotBlank(),
                    ) {
                        Text("Restore")
                    }
                }
            }
        }
    }
}
