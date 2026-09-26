package com.hermes.agent.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.data.export.AutoBackupScheduler
import com.hermes.agent.data.export.AutoBackupStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class AutoBackupUi(
    val enabled: Boolean,
    val folderName: String?,
    val everyHours: Int,
    val hasPassword: Boolean,
    val lastLine: String,
)

@HiltViewModel
class AutoBackupViewModel @Inject constructor(
    private val store: AutoBackupStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(read())
    val ui: StateFlow<AutoBackupUi> = _ui.asStateFlow()

    private fun read(): AutoBackupUi = AutoBackupUi(
        enabled = store.enabled,
        folderName = store.folder?.let { runCatching { Uri.parse(it).lastPathSegment?.substringAfterLast(':') }.getOrNull() },
        everyHours = store.everyHours,
        hasPassword = store.hasPassword && store.password() != null,
        lastLine = if (store.lastAt == 0L) "" else {
            "Last run ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(store.lastAt))}: ${store.lastResult}"
        },
    )

    fun refresh() {
        _ui.value = read()
    }

    fun chooseFolder(uri: Uri) {
        // The grant has to outlive this screen: the worker writes long after the picker closed.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        store.folder = uri.toString()
        apply()
    }

    fun setPassword(password: String) {
        store.setPassword(password)
        apply()
    }

    fun setEnabled(on: Boolean) {
        store.enabled = on
        apply()
    }

    fun setEveryHours(hours: Int) {
        store.everyHours = hours
        apply()
    }

    fun runNow() = AutoBackupScheduler.runNow(context)

    private fun apply() {
        AutoBackupScheduler.apply(context, store, replace = true)
        refresh()
    }
}

/** Scheduled full backups to a folder of the user's choosing, keeping the newest few. */
@Composable
fun AutoBackupSection(viewModel: AutoBackupViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.chooseFolder(uri)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic backups", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Saves a full encrypted backup on a schedule and keeps the newest 5.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = ui.enabled,
                    onCheckedChange = viewModel::setEnabled,
                    enabled = ui.folderName != null && ui.hasPassword,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(null) }) { Text(if (ui.folderName == null) "Choose folder" else "Change folder") }
                Text(
                    ui.folderName ?: "No folder chosen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (ui.hasPassword) "Password (saved; type to replace)" else "Password for these backups") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
            )
            if (password.isNotEmpty()) {
                OutlinedButton(
                    onClick = { viewModel.setPassword(password); password = "" },
                    enabled = password.length >= 8,
                ) { Text(if (password.length >= 8) "Save password" else "At least 8 characters") }
            }
            Text(
                "The password is kept on this phone, sealed with a key that cannot leave it, so backups can run unattended. " +
                    "You still need it to restore, so remember it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(24 to "Daily", 168 to "Weekly").forEach { (hours, label) ->
                    FilterChip(selected = ui.everyHours == hours, onClick = { viewModel.setEveryHours(hours) }, label = { Text(label) })
                }
            }
            if (ui.enabled) {
                OutlinedButton(onClick = viewModel::runNow) { Text("Back up now") }
            }
            if (ui.lastLine.isNotEmpty()) {
                Text(ui.lastLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
