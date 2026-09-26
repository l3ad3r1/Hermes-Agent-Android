package com.hermes.agent.ui.settings

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import com.hermes.agent.data.export.BackupSection
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

/** First bytes of every full backup file (the cipher's header magic). */
private val FULL_BACKUP_MAGIC = "HRMSFB01".toByteArray(Charsets.US_ASCII)

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

    /** True when the file starts with the marker every full backup begins with. */
    fun isFullBackup(uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(FULL_BACKUP_MAGIC.size)
            input.read(head) == head.size && head.contentEquals(FULL_BACKUP_MAGIC)
        } ?: false
    }.getOrDefault(false)

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

/**
 * The one place to back up and restore.
 *
 * "Everything" is ticked by default and makes a full backup: the app's whole storage in one
 * encrypted file, so nothing is left behind. Untick it to pick parts instead, which writes a
 * smaller file of just those parts that merges into what is already here. Restore takes either
 * kind and works out which it is from the file.
 */
@Composable
fun BackupRestoreSection(
    jsonState: BackupUiState,
    onJsonBackup: (Uri, Set<BackupSection>, String?, Boolean) -> Unit,
    onJsonRestore: (Uri, Boolean, String?) -> Unit,
    onJsonDismiss: () -> Unit,
    viewModel: FullBackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastRestore by viewModel.lastRestore.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var everything by remember { mutableStateOf(true) }
    // Credentials start unticked when picking parts: a backup is something people put in cloud
    // storage, so carrying keys has to be a deliberate act rather than the default.
    var selected by remember { mutableStateOf(BackupSection.DEFAULT) }
    var includeBots by remember { mutableStateOf(true) }
    var overwrite by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var pendingFullRestoreUri by remember { mutableStateOf<Uri?>(null) }

    val busy = state is FullBackupUiState.Working || jsonState is BackupUiState.InProgress
    val passwordRequired = everything || BackupSection.CREDENTIALS in selected
    val nothingPicked = !everything && selected.isEmpty() && !includeBots
    val canBackUp = !busy && !nothingPicked && (!passwordRequired || password.isNotBlank())

    val fullBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> if (uri != null) viewModel.backup(uri, password) }

    val partsBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) onJsonBackup(uri, selected, password.ifBlank { null }, includeBots) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // A full backup starts with a fixed marker; anything else is a chosen-parts file.
            if (viewModel.isFullBackup(uri)) pendingFullRestoreUri = uri else onJsonRestore(uri, overwrite, password.ifBlank { null })
        }
    }

    pendingFullRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingFullRestoreUri = null },
            title = { Text("Replace everything with this backup?") },
            text = {
                Text(
                    "Chats, settings, API keys, cron jobs, MCP servers, connectors and files will be replaced " +
                        "by what is in the backup. Your current database is kept as a safety copy. " +
                        "Hermes has to be closed and reopened to finish.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.stageRestore(uri, password); pendingFullRestoreUri = null }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingFullRestoreUri = null }) { Text("Cancel") } },
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Backup & Restore", style = MaterialTheme.typography.bodyLarge)
            }
            InfoNote(
                "What gets backed up",
                "Everything is one encrypted file of all Hermes keeps on this phone: chats and memory, every " +
                    "setting, API keys and tokens, providers, cron jobs, messaging, MCP servers, connectors, skills, " +
                    "modules, bots, board, notes, documents and the agent's workspace files. Untick it to choose " +
                    "parts instead; a file of chosen parts is merged into what is already here and needs no restart. " +
                    "You need the password to restore, and it cannot be recovered. Not included: downloaded models " +
                    "(download them again) and Android permissions (grant them again). The Tailscale sign-in is " +
                    "restored only on the same phone, and only from a full backup.",
            )

            CheckRow(
                checked = everything,
                onChange = { everything = it },
                label = "Everything",
                hint = " · full backup, recommended",
            )
            if (!everything) {
                BackupSection.entries.forEach { section ->
                    CheckRow(
                        checked = section in selected,
                        onChange = { selected = if (it) selected + section else selected - section },
                        label = section.label,
                        hint = if (section == BackupSection.CREDENTIALS) " · needs a password" else null,
                        indent = true,
                    )
                }
                CheckRow(
                    checked = includeBots,
                    onChange = { includeBots = it },
                    label = "Bots setup",
                    hint = " · phone bots, PC bots, Chief's name",
                    indent = true,
                )
            }

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (passwordRequired) "Password (required)" else "Password (optional)") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )

            if (!everything) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = overwrite, onCheckedChange = { overwrite = it })
                    Text(
                        if (overwrite) "Restoring a parts file replaces items that already exist" else "Restoring a parts file keeps your existing items",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                FilledTonalButton(
                    onClick = {
                        if (everything) fullBackupLauncher.launch(fullBackupFileName()) else partsBackupLauncher.launch(partsBackupFileName())
                    },
                    enabled = canBackUp,
                    modifier = Modifier.weight(1f),
                    contentPadding = SettingsButtonPadding,
                ) { ButtonLabel("Back up") }
                OutlinedButton(
                    onClick = { restoreLauncher.launch(arrayOf("*/*")) },
                    enabled = !busy && (password.isNotBlank() || !everything),
                    modifier = Modifier.weight(1f),
                    contentPadding = SettingsButtonPadding,
                ) { ButtonLabel("Restore") }
            }
            if (passwordRequired && password.isBlank()) {
                Text(
                    if (everything) "Enter a password first." else "Set a password to include cloud API keys.",
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
                    Button(onClick = { closeHermes(context) }, modifier = Modifier.fillMaxWidth()) { Text("Close Hermes now") }
                    OutlinedButton(onClick = viewModel::cancelStaged, modifier = Modifier.fillMaxWidth()) { Text("Cancel restore") }
                }
                FullBackupUiState.Idle -> Unit
            }

            when (jsonState) {
                is BackupUiState.InProgress -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Working…", style = MaterialTheme.typography.bodySmall)
                }
                is BackupUiState.Success -> {
                    Text(jsonState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = onJsonDismiss, modifier = Modifier.fillMaxWidth()) { Text("Dismiss") }
                }
                is BackupUiState.Error -> {
                    Text(jsonState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onJsonDismiss, modifier = Modifier.fillMaxWidth()) { Text("Dismiss") }
                }
                else -> Unit
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

@Composable
private fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String, hint: String?, indent: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(start = if (indent) 20.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun partsBackupFileName(): String =
    "hermes-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"
