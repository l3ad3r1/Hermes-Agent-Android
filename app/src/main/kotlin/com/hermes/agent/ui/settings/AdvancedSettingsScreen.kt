package com.hermes.agent.ui.settings
import com.hermes.agent.domain.settings.*

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.ui.theme.hermesFieldColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val localBackupState by viewModel.localBackupState.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val privilegedStatus by viewModel.privilegedStatus.collectAsStateWithLifecycle()
    val retryGateStatus by viewModel.privilegedRetryGateStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Advanced") },
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
            SectionHeader(text = "Privileged Shell (Shizuku)")
            PrivilegedShellSection(
                enabled = settings.privilegedShellEnabled,
                status = privilegedStatus,
                gateStatus = retryGateStatus,
                onToggleEnabled = viewModel::setPrivilegedShellEnabled,
                onRequestPermission = viewModel::requestPrivilegedPermission,
                onRefresh = viewModel::refreshPrivilegedStatus,
                onResetGate = viewModel::resetPrivilegedGate,
            )

            SectionHeader(text = "Local Backup & Restore")
            LocalBackupSection(
                state = localBackupState,
                onBackup = viewModel::createLocalBackup,
                onRestore = viewModel::restoreLocalBackup,
                onDismiss = viewModel::dismissLocalBackupState,
            )

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

        }
    }
}

@Composable
private fun PrivilegedShellSection(
    enabled: Boolean,
    status: com.hermes.agent.domain.device.PrivilegedShellBackend.PrivilegedStatus,
    gateStatus: com.hermes.agent.data.device.PrivilegedShellRetryGate.GateStatus,
    onToggleEnabled: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onResetGate: () -> Unit,
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable Privileged Shell", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Allows the shell tool to run with ADB privileges (UID 2000) via Shizuku.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = enabled,
                    onCheckedChange = onToggleEnabled,
                )
            }

            androidx.compose.material3.HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Shizuku Status",
                    style = MaterialTheme.typography.titleSmall,
                )
                androidx.compose.material3.TextButton(onClick = onRefresh) {
                    Text("Check Status")
                }
            }

            when (status.status) {
                com.hermes.agent.domain.device.PrivilegedShellBackend.Status.READY -> {
                    Text(
                        text = "● Connected (UID ${status.uid} · Version ${status.version})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                com.hermes.agent.domain.device.PrivilegedShellBackend.Status.PERMISSION_REQUIRED -> {
                    Text(
                        text = "⚠️ Permission Required: Hermes needs Shizuku access.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = onRequestPermission,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Grant Shizuku Permission")
                    }
                }
                com.hermes.agent.domain.device.PrivilegedShellBackend.Status.DEAD -> {
                    Text(
                        text = "⚠️ Shizuku service is not running.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "Start it via ADB:\n${com.hermes.agent.data.device.PrivilegedShellGateway.ADB_START_COMMAND}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("ADB Command", com.hermes.agent.data.device.PrivilegedShellGateway.ADB_START_COMMAND)
                            clipboard.setPrimaryClip(clip)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Copy ADB Command")
                    }
                }
                com.hermes.agent.domain.device.PrivilegedShellBackend.Status.NOT_INSTALLED -> {
                    Text(
                        text = "Shizuku app is not installed on this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (gateStatus.state == com.hermes.agent.data.device.PrivilegedShellRetryGate.State.DIRTY_UNWIND) {
                androidx.compose.material3.HorizontalDivider()
                Text(
                    text = "⚠️ Execution Gate Locked: ${gateStatus.reason}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(
                    onClick = onResetGate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reset Execution Gate")
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
private fun LocalBackupSection(
    state: BackupUiState,
    onBackup: () -> Unit,
    onRestore: (android.net.Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val restoreLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onRestore(uri)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Outlined.SaveAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text("On-Device Backup", style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                // Deliberately does not name a folder. The export falls back
                // across three locations depending on storage permission, and
                // this line used to promise Download/… for a file that lands in
                // Hermes Agent/Backup. The success message reports the path the
                // file actually went to.
                "Creates a complete snapshot of all app data, chats, and settings. " +
                "The folder it lands in is shown once the backup finishes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
                    ) {
                        Text("Backup to Device")
                    }
                    OutlinedButton(
                        onClick = { restoreLauncher.launch(arrayOf("application/zip")) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Restore")
                    }
                }
            }
        }
    }
}
