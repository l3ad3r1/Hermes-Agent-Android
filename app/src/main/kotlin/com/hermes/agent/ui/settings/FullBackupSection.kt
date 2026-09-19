package com.hermes.agent.ui.settings

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.export.FullBackupManager
import com.hermes.agent.data.export.FullBackupSummary
import com.hermes.agent.data.export.RestoreResult
import com.hermes.agent.data.export.StagedRestore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

sealed interface FullBackupUiState {
    data object Idle : FullBackupUiState
    data class Working(val what: String) : FullBackupUiState
    data class BackupDone(val message: String) : FullBackupUiState
    /** A verified backup is waiting for Hermes to be closed and reopened. */
    data class RestoreStaged(val message: String) : FullBackupUiState
    data class Error(val message: String) : FullBackupUiState
}

@HiltViewModel
class FullBackupViewModel @Inject constructor(
    private val manager: FullBackupManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<FullBackupUiState>(
        if (manager.isRestorePending()) {
            FullBackupUiState.RestoreStaged("A backup is verified and waiting. Close Hermes and open it again to finish the restore.")
        } else {
            FullBackupUiState.Idle
        },
    )
    val state: StateFlow<FullBackupUiState> = _state.asStateFlow()

    /** What the launch that applied the last restore did, shown until dismissed. */
    private val _lastRestore = MutableStateFlow(manager.lastRestoreResult())
    val lastRestore: StateFlow<RestoreResult?> = _lastRestore.asStateFlow()

    fun backup(uri: Uri, password: String) {
        if (_state.value is FullBackupUiState.Working) return
        _state.value = FullBackupUiState.Working("Backing up everything…")
        viewModelScope.launch {
            _state.value = runCatching {
                val out = context.contentResolver.openOutputStream(uri) ?: error("Could not open the file for writing.")
                manager.backup(out, password)
            }.onFailure {
                // The file picker has already created the file; a failed backup must not leave an
                // empty one behind that looks like a backup.
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
            }.fold(
                onSuccess = { FullBackupUiState.BackupDone(describe(it)) },
                onFailure = { FullBackupUiState.Error(it.message ?: "The backup failed.") },
            )
        }
    }

    fun stageRestore(uri: Uri, password: String) {
        if (_state.value is FullBackupUiState.Working) return
        _state.value = FullBackupUiState.Working("Checking the backup…")
        viewModelScope.launch {
            _state.value = runCatching {
                val input = context.contentResolver.openInputStream(uri) ?: error("Could not open the file for reading.")
                input.use { manager.stageRestore(it, password) }
            }.fold(
                onSuccess = { FullBackupUiState.RestoreStaged(describe(it)) },
                onFailure = { FullBackupUiState.Error(it.message ?: "The backup could not be read.") },
            )
        }
    }

    fun cancelStaged() {
        manager.cancelPendingRestore()
        _state.value = FullBackupUiState.Idle
    }

    fun dismiss() {
        _state.value = FullBackupUiState.Idle
    }

    fun dismissLastRestore() {
        manager.clearRestoreResult()
        _lastRestore.value = null
    }

    private fun describe(s: FullBackupSummary): String = buildString {
        append("Backed up everything: the database (${megabytes(s.databaseBytes)}), ${s.settings} settings, ")
        append("${s.prefsFiles} preference files and ${s.files} files, encrypted.")
        if (s.skipped.isNotEmpty()) append(" Left out: ${s.skipped.joinToString("; ")}.")
    }

    private fun describe(s: StagedRestore): String = buildString {
        val made = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.manifest.exportedAt))
        append("Backup verified (made $made by Hermes ${s.manifest.appVersionName}). ")
        append("Close Hermes and open it again to finish the restore.")
        if (!s.sameDevice) append(" It came from another phone, so its Tailscale sign-in will not be applied.")
    }

    private fun megabytes(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
}

@Composable
fun FullBackupSection(viewModel: FullBackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastRestore by viewModel.lastRestore.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    val busy = state is FullBackupUiState.Working
    val hasPassword = password.isNotEmpty()

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> if (uri != null) viewModel.backup(uri, password) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingRestoreUri = uri }

    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("Replace everything with this backup?") },
            text = {
                Text(
                    "Chats, settings, API keys, cron jobs, MCP servers, connectors and files will be replaced " +
                        "by what is in the backup. Your current database is kept as a safety copy. " +
                        "Hermes has to be closed and reopened to finish.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.stageRestore(uri, password); pendingRestoreUri = null }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingRestoreUri = null }) { Text("Cancel") } },
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Full backup", style = MaterialTheme.typography.bodyLarge)
            }
            InfoNote(
                "What a full backup holds",
                "Everything Hermes keeps on this phone: chats and memory, every setting, API keys and tokens, " +
                    "providers, cron jobs, messaging, MCP servers, connectors, skills, modules, bots, board, notes, " +
                    "documents and the agent's workspace files. It is one file, encrypted with your password, " +
                    "which you need to restore it and which cannot be recovered. Not included: downloaded models " +
                    "(download them again) and Android permissions (grant them again). The Tailscale sign-in is " +
                    "restored only on the same phone.",
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password (required)") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(
                    onClick = { backupLauncher.launch(fullBackupFileName()) },
                    enabled = hasPassword && !busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = SettingsButtonPadding,
                ) { ButtonLabel("Back up") }
                OutlinedButton(
                    onClick = { restoreLauncher.launch(arrayOf("*/*")) },
                    enabled = hasPassword && !busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = SettingsButtonPadding,
                ) { ButtonLabel("Restore") }
            }
            if (!hasPassword) {
                Text(
                    "Enter a password first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (val s = state) {
                is FullBackupUiState.Working -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(s.what, style = MaterialTheme.typography.bodySmall)
                }
                is FullBackupUiState.BackupDone -> {
                    Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = viewModel::dismiss, modifier = Modifier.fillMaxWidth()) { Text("Dismiss") }
                }
                is FullBackupUiState.Error -> {
                    Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = viewModel::dismiss, modifier = Modifier.fillMaxWidth()) { Text("Dismiss") }
                }
                is FullBackupUiState.RestoreStaged -> {
                    Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Button(
                        onClick = { closeHermes(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Close Hermes now") }
                    OutlinedButton(onClick = viewModel::cancelStaged, modifier = Modifier.fillMaxWidth()) { Text("Cancel restore") }
                }
                FullBackupUiState.Idle -> Unit
            }

            lastRestore?.let { r ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        (if (r.ok) "Last restore: " else "Last restore, with problems: ") +
                            SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(r.at)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(r.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    r.problems.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = viewModel::dismissLastRestore, modifier = Modifier.fillMaxWidth()) { Text("Dismiss") }
                }
            }
        }
    }
}

/** Ends this process so the next launch applies the staged restore before anything is open. */
private fun closeHermes(context: Context) {
    (context as? Activity)?.finishAffinity()
    android.os.Process.killProcess(android.os.Process.myPid())
}

private fun fullBackupFileName(): String =
    "hermes-full-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".hbk"
