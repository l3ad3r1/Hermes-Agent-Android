package com.hermes.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.data.llm.CloudProviderDefinition
import com.hermes.agent.data.llm.CloudProviderRegistry
import com.hermes.agent.domain.settings.CloudProviderProfile
import com.hermes.agent.ui.theme.hermesFieldColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val modelDiscovery by viewModel.providerModelDiscovery.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Providers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate back")
                    }
                },
                // No add action here. "Add Provider" sits beside the
                // "Configured Providers" heading and is the single way in;
                // duplicating it as a bare + up here only made the user wonder
                // whether the two did different things.
            )
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleRow(
                        title = "Cloud routing",
                        subtitle = "Hermes chooses the best configured cloud model, then uses local AI only if every cloud provider fails.",
                        checked = settings.cloudEnabled,
                        onCheckedChange = viewModel::setCloudEnabled,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Configured Providers (${settings.cloudProviderProfiles.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Provider")
                }
            }

            if (settings.cloudProviderProfiles.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "No cloud providers configured yet.",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "Add a provider (such as OpenAI, OpenRouter, Nous, Gemini, Groq, DeepSeek) or a custom local/remote OpenAI-compatible endpoint.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // No button here either: "Add Provider" sits directly
                        // above this card, next to the heading, and is shown
                        // whether or not any provider exists.
                    }
                }
            } else {
                settings.cloudProviderProfiles.forEach { profile ->
                    val definition = CloudProviderRegistry.definition(profile.id)
                    ProviderCredentialCard(
                        profile = profile,
                        definition = definition,
                        modelState = modelDiscovery[profile.id] ?: ModelDiscoveryUiState.Idle,
                        onApiKeyChange = { viewModel.setProviderApiKey(profile.id, it) },
                        onEnabledChange = { viewModel.setProviderEnabled(profile.id, it) },
                        onBaseUrlChange = { viewModel.setProviderBaseUrl(profile.id, it) },
                        onModelChange = { viewModel.setProviderModel(profile.id, it) },
                        onRefreshModels = { viewModel.refreshProviderModels(profile.id) },
                        onRemove = { viewModel.removeProvider(profile.id) },
                        onStartOAuth = { ctx -> viewModel.startOAuthFlow(profile.id, ctx) },
                    )
                }
            }

            Text(
                "Provider keys are stored with Android Keystore encryption.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showAddDialog) {
        AddProviderDialog(
            existingProviderIds = settings.cloudProviderProfiles.map { it.id }.toSet(),
            onDismiss = { showAddDialog = false },
            onAdd = { presetId, name, baseUrl, apiKey, reasoningEffort ->
                viewModel.addProvider(
                    definitionId = presetId,
                    customName = name,
                    customBaseUrl = baseUrl,
                    apiKey = apiKey,
                    reasoningEffort = reasoningEffort,
                )
                showAddDialog = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProviderDialog(
    existingProviderIds: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (presetId: String, name: String, baseUrl: String, apiKey: String, reasoningEffort: String) -> Unit,
) {
    var selectedPresetId by remember { mutableStateOf("custom") }
    var expanded by remember { mutableStateOf(false) }
    var customName by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }

    val isCustom = selectedPresetId == "custom"
    val selectedDefinition = remember(selectedPresetId) {
        CloudProviderRegistry.definition(selectedPresetId)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Cloud Provider") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Dropdown to pick provider preset or custom
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = if (isCustom) "Custom (OpenAI Compatible)" else (selectedDefinition?.name ?: selectedPresetId),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Provider Preset") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = hermesFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Custom (OpenAI Compatible)") },
                            onClick = {
                                selectedPresetId = "custom"
                                customName = "Custom Provider"
                                baseUrl = ""
                                expanded = false
                            },
                        )
                        HorizontalDivider()
                        CloudProviderRegistry.providers.forEach { preset ->
                            val alreadyAdded = preset.id in existingProviderIds
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = preset.name + if (alreadyAdded) " (Added)" else "",
                                        color = if (alreadyAdded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                onClick = {
                                    selectedPresetId = preset.id
                                    customName = preset.name
                                    baseUrl = preset.defaultBaseUrl
                                    expanded = false
                                },
                            )
                        }
                    }
                }

                if (isCustom) {
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Provider Name") },
                        placeholder = { Text("e.g. Local Ollama / vLLM") },
                        singleLine = true,
                        colors = hermesFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = {
                        Text(if (isCustom) "http://192.168.1.100:11434/v1" else selectedDefinition?.defaultBaseUrl.orEmpty())
                    },
                    supportingText = { Text("OpenAI-compatible /v1 endpoint") },
                    singleLine = true,
                    colors = hermesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text(if (isCustom) "Optional for local endpoints" else "Paste API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = hermesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Reasoning effort is not set per provider here — it is driven
                // from the chat composer (and Maestro routing aliases) at send
                // time, so every provider is added with "inherit".
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = if (isCustom) customName.ifBlank { "Custom Provider" } else (selectedDefinition?.name ?: "Provider")
                    val finalUrl = baseUrl.ifBlank { selectedDefinition?.defaultBaseUrl.orEmpty() }
                    onAdd(selectedPresetId, finalName, finalUrl, apiKey, "")
                },
                enabled = baseUrl.isNotBlank() || !isCustom,
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ProviderCredentialCard(
    profile: CloudProviderProfile,
    definition: CloudProviderDefinition?,
    modelState: ModelDiscoveryUiState,
    onApiKeyChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onRemove: () -> Unit,
    onStartOAuth: (android.content.Context) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var apiKey by remember(profile.apiKey) { mutableStateOf(profile.apiKey) }
    var baseUrl by remember(profile.baseUrl) { mutableStateOf(profile.baseUrl) }

    DisposableEffect(profile.id) {
        onDispose {
            if (apiKey != profile.apiKey) onApiKeyChange(apiKey)
            if (baseUrl != profile.baseUrl) onBaseUrlChange(baseUrl)
        }
    }

    LaunchedEffect(profile.id, profile.apiKey, profile.baseUrl) {
        if (profile.apiKey.isNotBlank() || profile.id.startsWith("custom_")) {
            onRefreshModels()
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        definition?.description ?: profile.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = profile.enabled,
                    enabled = apiKey.isNotBlank() || profile.id.startsWith("custom_"),
                    onCheckedChange = onEnabledChange,
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Remove provider",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (profile.id == "openrouter" || profile.id == "nous") {
                OutlinedButton(
                    onClick = { onStartOAuth(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (apiKey.isNotBlank()) "Re-authenticate with ${profile.name}" else "Sign in with ${profile.name}")
                }
            }

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("${profile.name} API key") },
                placeholder = { Text(if (profile.id.startsWith("custom_")) "Optional for local endpoints" else "Paste key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focus ->
                        if (!focus.isFocused && apiKey != profile.apiKey) {
                            onApiKeyChange(apiKey)
                        }
                    },
            )

            ProviderModelDropdown(
                selectedModel = profile.model,
                state = modelState,
                onSelect = onModelChange,
                onRetry = onRefreshModels,
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focus ->
                        if (!focus.isFocused && baseUrl != profile.baseUrl) {
                            onBaseUrlChange(baseUrl)
                        }
                    },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderModelDropdown(
    selectedModel: String,
    state: ModelDiscoveryUiState,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val models = (state as? ModelDiscoveryUiState.Ready)?.models.orEmpty()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (models.isNotEmpty()) expanded = it },
    ) {
        OutlinedTextField(
            value = selectedModel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Model") },
            supportingText = {
                Text(
                    when (state) {
                        ModelDiscoveryUiState.Idle -> "Enter Base URL / API key to load available models."
                        ModelDiscoveryUiState.Loading -> "Loading available models…"
                        ModelDiscoveryUiState.Empty -> "No chat models were returned by this provider."
                        is ModelDiscoveryUiState.Error -> state.message
                        is ModelDiscoveryUiState.Ready -> "${state.models.size} available · best choice preselected"
                    },
                )
            },
            trailingIcon = {
                if (state is ModelDiscoveryUiState.Loading) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            colors = hermesFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { model ->
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
    if (state is ModelDiscoveryUiState.Error || state is ModelDiscoveryUiState.Empty) {
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Retry loading models") }
    }
}
