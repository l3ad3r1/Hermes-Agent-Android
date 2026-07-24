package com.hermes.agent.ui.settings

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
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
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
                "Backs up Cloud LLM settings (excluding credentials), memories, " +
                    "skills, cron jobs, notes, and alarms to a private GitHub Gist — " +
                    "Locked or encrypted notes are skipped. Keep the PAT and gist safe. " +
                    "To restore on a new install, paste " +
                    "the same PAT and Gist ID below, then tap Restore.",
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
