package com.hermes.agent.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.hermes.agent.data.export.CloudBackupPolicy
import com.hermes.agent.data.export.CloudBackupScheduler
import com.hermes.agent.data.export.CloudBackupService
import com.hermes.agent.data.export.CloudBackupStore
import com.hermes.agent.data.export.CloudFile
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class CloudBackupUi(
    val repo: String = "",
    val device: String = "",
    val auto: Boolean = false,
    val hasToken: Boolean = false,
    val hasPassword: Boolean = false,
    val working: String? = null,
    val message: String = "",
    val isError: Boolean = false,
    val files: List<CloudFile>? = null,
    val staged: Boolean = false,
    val lastLine: String = "",
)

@HiltViewModel
class CloudBackupViewModel @Inject constructor(
    private val store: CloudBackupStore,
    private val service: CloudBackupService,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(read())
    val ui: StateFlow<CloudBackupUi> = _ui.asStateFlow()

    private fun read() = CloudBackupUi(
        repo = store.repo,
        device = store.device,
        auto = store.auto,
        hasToken = store.token() != null,
        hasPassword = store.password() != null,
        lastLine = if (store.lastAt == 0L) "" else {
            "Last upload ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(store.lastAt))}: ${store.lastResult}"
        },
    )

    /** Saves what was typed; blank token and password fields keep what is already saved. */
    fun save(repo: String, device: String, token: String, password: String) {
        if (!CloudBackupPolicy.isRepo(repo)) {
            _ui.update { it.copy(message = "Enter the repo as owner/name, for example l3ad3r1/jeeves-backups.", isError = true) }
            return
        }
        store.repo = repo
        store.device = device.ifBlank { android.os.Build.MODEL.orEmpty() }
        if (token.isNotBlank()) store.setToken(token)
        if (password.isNotEmpty()) store.setPassword(password)
        CloudBackupScheduler.apply(context, store, replace = true)
        _ui.update { read().copy(message = "Saved.", isError = false, files = it.files) }
    }

    fun setAuto(on: Boolean) {
        store.auto = on
        CloudBackupScheduler.apply(context, store, replace = true)
        _ui.update { read().copy(files = it.files) }
    }

    fun upload(password: String) {
        val pw = password.ifEmpty { store.password() }
        if (pw.isNullOrEmpty()) {
            _ui.update { it.copy(message = "Enter a backup password first.", isError = true) }
            return
        }
        launchWork("Backing up and uploading…") {
            service.upload(pw).fold(
                onSuccess = { m -> _ui.update { read().copy(message = m, isError = false, files = it.files) } },
                onFailure = { e -> _ui.update { read().copy(message = e.message ?: "The upload failed.", isError = true, files = it.files) } },
            )
        }
    }

    fun refresh() = launchWork("Reading the cloud backups…") {
        service.list().fold(
            onSuccess = { list -> _ui.update { it.copy(files = list, message = if (list.isEmpty()) "No backups in the repo yet." else "", isError = false, working = null) } },
            onFailure = { e -> _ui.update { it.copy(message = e.message ?: "Could not read the repo.", isError = true, working = null) } },
        )
    }

    fun restore(file: CloudFile, password: String) {
        val pw = password.ifEmpty { store.password() }
        if (pw.isNullOrEmpty()) {
            _ui.update { it.copy(message = "Enter the backup password first.", isError = true) }
            return
        }
        launchWork("Downloading and checking…") {
            service.stage(file, pw).fold(
                onSuccess = { s ->
                    val note = if (!s.sameDevice) " It came from another device, so its Tailscale sign-in will not be applied." else ""
                    _ui.update { it.copy(staged = true, message = "Backup verified. Close Hermes and open it again to finish the restore.$note", isError = false, working = null) }
                },
                onFailure = { e -> _ui.update { it.copy(message = e.message ?: "The backup could not be read.", isError = true, working = null) } },
            )
        }
    }

    private fun launchWork(what: String, block: suspend () -> Unit) {
        if (_ui.value.working != null) return
        _ui.update { it.copy(working = what) }
        viewModelScope.launch {
            block()
            _ui.update { it.copy(working = null) }
        }
    }
}

/** Keeps encrypted full backups in a private GitHub repo, so several devices can share them. */
@Composable
fun CloudBackupSection(viewModel: CloudBackupViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var repo by remember(ui.repo) { mutableStateOf(ui.repo) }
    var device by remember(ui.device) { mutableStateOf(ui.device) }
    var token by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<CloudFile?>(null) }
    val hidden = if (show) VisualTransformation.None else PasswordVisualTransformation()

    confirm?.let { file ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Replace everything with this backup?") },
            text = {
                Text(
                    "Chats, settings, API keys and files will be replaced by ${file.name} from ${file.device}. " +
                        "Your current database is kept as a safety copy. Hermes has to be closed and reopened to finish.",
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.restore(file, password); confirm = null }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Cloud backup (GitHub)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Keeps encrypted full backups in a private repo, one folder per device, so a tablet and a phone can restore each other's.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = ui.auto, onCheckedChange = viewModel::setAuto, enabled = ui.hasToken && ui.hasPassword && CloudBackupPolicy.isRepo(ui.repo))
            }
            OutlinedTextField(
                value = repo, onValueChange = { repo = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Private repo (owner/name)") },
            )
            OutlinedTextField(
                value = device, onValueChange = { device = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("This device's name") },
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (ui.hasToken) "GitHub token (saved; type to replace)" else "GitHub token (Contents: read and write)") },
                visualTransformation = hidden,
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (ui.hasPassword) "Backup password (saved; type to replace)" else "Backup password") },
                visualTransformation = hidden,
                trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
            )
            Text(
                "The token and password are kept on this device, sealed with a key that cannot leave it. Use the same " +
                    "password on every device that should read these backups; it cannot be recovered. Each backup is " +
                    "encrypted before it leaves the device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { viewModel.save(repo, device, token, password); token = ""; if (password.length >= 8) password = "" },
                    enabled = repo.isNotBlank() && (password.isEmpty() || password.length >= 8),
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
                Button(
                    onClick = { viewModel.upload(password) },
                    enabled = ui.working == null && ui.hasToken && (ui.hasPassword || password.length >= 8),
                    modifier = Modifier.weight(1f),
                ) { Text("Upload now") }
            }
            OutlinedButton(
                onClick = viewModel::refresh,
                enabled = ui.working == null && ui.hasToken,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Show cloud backups") }

            ui.working?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (ui.message.isNotEmpty()) {
                Text(
                    ui.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            if (ui.staged) {
                Button(onClick = { closeHermes(context) }, modifier = Modifier.fillMaxWidth()) { Text("Close Hermes now") }
            }
            ui.files?.forEach { file ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(file.device, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${file.name.removePrefix("hermes-").removeSuffix(CloudBackupPolicy.SUFFIX)} · ${file.size / 1024} KB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { confirm = file }, enabled = ui.working == null && (ui.hasPassword || password.isNotEmpty())) { Text("Restore") }
                }
            }
            if (ui.lastLine.isNotEmpty()) {
                Text(ui.lastLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
