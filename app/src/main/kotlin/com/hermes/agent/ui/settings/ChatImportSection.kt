package com.hermes.agent.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.export.ChatExportImporter
import com.hermes.agent.data.export.ImportMode
import com.hermes.agent.data.export.JsonBackup
import com.hermes.agent.data.export.JsonBackupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ChatImportViewModel @Inject constructor(
    private val backups: JsonBackupManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    fun import(uri: Uri) {
        if (_working.value) return
        _working.value = true
        _message.value = null
        viewModelScope.launch {
            _message.value = try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not open the file.")
                }
                val parsed = withContext(Dispatchers.Default) { ChatExportImporter.parse(bytes) }
                if (parsed.conversations.isEmpty()) {
                    "No chats with messages were found in that file."
                } else {
                    val report = backups.import(
                        JsonBackup(conversations = parsed.conversations),
                        ImportMode.SKIP_EXISTING,
                    )
                    buildString {
                        append("Imported ${report.added} ${parsed.source.label} chat${if (report.added == 1) "" else "s"}")
                        if (report.skipped > 0) append(", ${report.skipped} already here")
                        append(". Only the text of your messages and the replies comes across, not images or files.")
                    }
                }
            } catch (e: ChatExportImporter.ImportFailure) {
                e.message ?: "That file could not be read."
            } catch (e: Exception) {
                "The import failed: ${e.message ?: e.javaClass.simpleName}"
            }
            _working.value = false
        }
    }
}

/** Brings chats in from a ChatGPT or Claude "export your data" file (the zip or its conversations.json). */
@Composable
fun ChatImportSection(viewModel: ChatImportViewModel = hiltViewModel()) {
    val message by viewModel.message.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Import chats from ChatGPT or Claude", style = MaterialTheme.typography.titleMedium)
            Text(
                "In ChatGPT or Claude, request \"Export data\" from settings, then pick the zip you receive (or its conversations.json). " +
                    "Chats already imported are skipped, so it is safe to import the same file again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (working) {
                CircularProgressIndicator()
            } else {
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "text/plain")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Choose export file") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
