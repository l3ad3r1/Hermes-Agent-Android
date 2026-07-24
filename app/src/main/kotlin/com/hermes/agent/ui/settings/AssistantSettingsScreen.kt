package com.hermes.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.R
import com.hermes.agent.ui.theme.hermesFieldColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assistant") },
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
            SectionHeader(text = stringResource(R.string.settings_section_cloud))
            CloudSection(settings = settings, viewModel = viewModel)

            SectionHeader(text = "On-Device AI (Local Engine)")
            OnDeviceAiCard(settings = settings, viewModel = viewModel)

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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnDeviceAiCard(
    settings: com.hermes.agent.data.settings.UserSettings,
    viewModel: SettingsViewModel,
) {
    val isDownloaded by viewModel.isModelDownloaded.collectAsStateWithLifecycle()
    val isDownloading by viewModel.isModelDownloading.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.modelDownloadProgress.collectAsStateWithLifecycle()
    val downloadError by viewModel.modelDownloadError.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val selectedModel = com.hermes.agent.data.llm.ModelCatalog.byId(settings.selectedModelId)

    // Storage-access state; re-checked when the user returns from the grant flow.
    var hasStorage by remember { mutableStateOf(viewModel.hasStorageAccess()) }
    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasStorage = viewModel.hasStorageAccess()
        viewModel.onStorageAccessMaybeChanged()
    }
    val writePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        hasStorage = viewModel.hasStorageAccess()
        viewModel.onStorageAccessMaybeChanged()
    }
    fun requestStorageAccess() {
        val intent = viewModel.allFilesAccessIntent()
        if (intent != null) allFilesLauncher.launch(intent)
        else writePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    val customPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // takePersistableUriPermission throws SecurityException for some
            // providers/URIs; an uncaught throw here (activity-result callback,
            // main thread) crashes the app on model pick. Persisting is
            // best-effort — the URI still works for this session either way.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.setLocalModelUri(uri.toString())
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "Cloud models are preferred when enabled. This on-device model is the private " +
                    "offline fallback — pick a model, choose where to save it, and download.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Model dropdown ──────────────────────────────────────────────
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { if (!isDownloading) expanded = it },
            ) {
                OutlinedTextField(
                    value = "${selectedModel.displayName} · ${selectedModel.sizeLabel}",
                    onValueChange = {},
                    readOnly = true,
                    enabled = !isDownloading,
                    label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    colors = hermesFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    viewModel.modelCatalog.forEach { model ->
                        DropdownMenuItem(
                            text = { Text("${model.displayName} · ${model.sizeLabel}") },
                            onClick = {
                                viewModel.setSelectedModelId(model.id)
                                expanded = false
                            },
                        )
                    }
                }
            }

            // ── Download folder ─────────────────────────────────────────────
            var dirText by remember(settings.modelDownloadDir) { mutableStateOf(settings.modelDownloadDir) }
            OutlinedTextField(
                value = dirText,
                onValueChange = {
                    dirText = it
                    viewModel.setModelDownloadDir(it)
                },
                enabled = !isDownloading,
                label = { Text("Download folder") },
                placeholder = { Text("/storage/emulated/0/${viewModel.defaultModelDirName}") },
                supportingText = {
                    Text("Leave blank to use the default \"${viewModel.defaultModelDirName}\" folder in internal storage.")
                },
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Storage permission gate ─────────────────────────────────────
            if (!hasStorage) {
                Text(
                    text = "Storage access is needed to save the model into a folder you can see. " +
                        "Without it, downloads can't be saved there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                androidx.compose.material3.OutlinedButton(
                    onClick = { requestStorageAccess() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Grant storage access")
                }
            }

            // ── Error surface (L-007) ───────────────────────────────────────
            if (downloadError.isNotBlank()) {
                Text(
                    text = downloadError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                androidx.compose.material3.TextButton(onClick = { viewModel.clearModelDownloadError() }) {
                    Text("Dismiss")
                }
            }

            // ── State-driven actions ────────────────────────────────────────
            when {
                settings.localModelUri.isNotBlank() -> {
                    Text(
                        text = "Using a custom model from device storage.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    androidx.compose.material3.Button(
                        onClick = { viewModel.setLocalModelUri("") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("Clear custom model") }
                }
                isDownloading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Downloading… (${(downloadProgress * 100).toInt()}%)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                isDownloaded -> {
                    Text(
                        text = "${selectedModel.displayName} is downloaded and ready.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    androidx.compose.material3.OutlinedButton(
                        onClick = { customPicker.launch(arrayOf("application/octet-stream")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Pick a custom model (.gguf) instead") }
                }
                else -> {
                    androidx.compose.material3.Button(
                        onClick = { viewModel.downloadLocalModel() },
                        enabled = hasStorage,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Download ${selectedModel.displayName} (${selectedModel.sizeLabel})") }
                    androidx.compose.material3.OutlinedButton(
                        onClick = { customPicker.launch(arrayOf("application/octet-stream")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Pick a custom model (.gguf) instead") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudSection(
    settings: com.hermes.agent.data.settings.UserSettings,
    viewModel: SettingsViewModel,
) {
    val primaryDiscovery by viewModel.primaryModelDiscovery.collectAsStateWithLifecycle()
    val specialistDiscovery by viewModel.specialistModelDiscovery.collectAsStateWithLifecycle()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleRow(
                title = stringResource(R.string.settings_cloud_enabled),
                subtitle = "Cloud is tried first when configured; the on-device model is the offline fallback.",
                checked = settings.cloudEnabled,
                onCheckedChange = viewModel::setCloudEnabled,
            )
            HorizontalDivider()

            Text(
                "Primary provider — general tasks",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var baseUrl by remember(settings.cloudBaseUrl) { mutableStateOf(settings.cloudBaseUrl) }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    viewModel.setCloudBaseUrl(it)
                },
                label = { Text(stringResource(R.string.settings_cloud_base_url)) },
                supportingText = { Text("Models load automatically from this URL's /models endpoint.") },
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            var apiKey by remember(settings.cloudApiKey) { mutableStateOf(settings.cloudApiKey) }
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    viewModel.setCloudApiKey(it)
                },
                label = { Text(stringResource(R.string.settings_cloud_api_key)) },
                supportingText = { Text(stringResource(R.string.settings_cloud_api_key_subtitle)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            CloudModelSelector(
                label = stringResource(R.string.settings_cloud_model),
                selectedModel = settings.cloudModel,
                state = primaryDiscovery,
                onSelect = viewModel::setCloudModel,
                onRetry = viewModel::refreshCloudModels,
            )

            HorizontalDivider()

            Text(
                "Specialist provider — complex tasks",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var auxBaseUrl by remember(settings.auxBaseUrl) { mutableStateOf(settings.auxBaseUrl) }
            OutlinedTextField(
                value = auxBaseUrl,
                onValueChange = {
                    auxBaseUrl = it
                    viewModel.setAuxBaseUrl(it)
                },
                label = { Text("Specialist base URL (optional)") },
                supportingText = { Text("Leave blank to reuse the primary provider and its model list.") },
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
                supportingText = { Text("Leave blank to reuse the primary provider's key.") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            CloudModelSelector(
                label = stringResource(R.string.settings_specialised_model),
                selectedModel = settings.auxModel,
                state = specialistDiscovery,
                onSelect = viewModel::setAuxModel,
                onRetry = viewModel::refreshCloudModels,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudModelSelector(
    label: String,
    selectedModel: String,
    state: ModelDiscoveryUiState,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    if (state is ModelDiscoveryUiState.Ready) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = selectedModel,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                supportingText = {
                    if (selectedModel !in state.models) {
                        Text("The saved model is not available at this endpoint. Select an available model.")
                    } else {
                        Text("${state.models.size} models available")
                    }
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = hermesFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = {
                            onSelect(model)
                            expanded = false
                        },
                    )
                }
            }
        }
        return
    }

    OutlinedTextField(
        value = selectedModel,
        onValueChange = {},
        readOnly = true,
        enabled = false,
        label = { Text(label) },
        supportingText = {
            Text(
                when (state) {
                    ModelDiscoveryUiState.Idle -> "Enable cloud and enter an API URL to load models."
                    ModelDiscoveryUiState.Loading -> "Loading available models…"
                    ModelDiscoveryUiState.Empty -> "This endpoint returned no models. Check the URL or provider."
                    is ModelDiscoveryUiState.Error -> state.message
                    is ModelDiscoveryUiState.Ready -> ""
                }
            )
        },
        trailingIcon = {
            if (state is ModelDiscoveryUiState.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        },
        colors = hermesFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )

    if (state is ModelDiscoveryUiState.Error || state is ModelDiscoveryUiState.Empty) {
        androidx.compose.material3.OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Retry loading models")
        }
    }
}
