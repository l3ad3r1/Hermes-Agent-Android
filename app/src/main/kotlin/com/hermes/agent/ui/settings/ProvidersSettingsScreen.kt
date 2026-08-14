package com.hermes.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.hermes.agent.data.settings.CloudProviderProfile
import com.hermes.agent.ui.theme.hermesFieldColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val modelDiscovery by viewModel.providerModelDiscovery.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    val configured = settings.cloudProviderProfiles.associateBy { it.id }
    val visible = CloudProviderRegistry.providers.filter {
        search.isBlank() || it.name.contains(search, ignoreCase = true) ||
            it.description.contains(search, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Providers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search providers") },
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            visible.forEach { definition ->
                ProviderCredentialCard(
                    definition = definition,
                    profile = configured[definition.id],
                    modelState = modelDiscovery[definition.id] ?: ModelDiscoveryUiState.Idle,
                    onApiKeyChange = { viewModel.setProviderApiKey(definition.id, it) },
                    onEnabledChange = { viewModel.setProviderEnabled(definition.id, it) },
                    onBaseUrlChange = { viewModel.setProviderBaseUrl(definition.id, it) },
                    onModelChange = { viewModel.setProviderModel(definition.id, it) },
                    onRefreshModels = { viewModel.refreshProviderModels(definition.id) },
                )
            }
            Text(
                "Provider keys are stored with Android Keystore encryption.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProviderCredentialCard(
    definition: CloudProviderDefinition,
    profile: CloudProviderProfile?,
    modelState: ModelDiscoveryUiState,
    onApiKeyChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onRefreshModels: () -> Unit,
) {
    val initial = profile ?: CloudProviderRegistry.profile(definition)
    var apiKey by remember(profile?.apiKey) { mutableStateOf(profile?.apiKey.orEmpty()) }
    var baseUrl by remember(profile?.baseUrl) { mutableStateOf(initial.baseUrl) }

    LaunchedEffect(definition.id, profile?.apiKey, profile?.baseUrl) {
        if (!profile?.apiKey.isNullOrBlank()) {
            onRefreshModels()
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(definition.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        definition.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = profile?.enabled == true && apiKey.isNotBlank(),
                    enabled = apiKey.isNotBlank(),
                    onCheckedChange = onEnabledChange,
                )
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("${definition.name} API key") },
                placeholder = { Text("Paste ${definition.name} key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                colors = hermesFieldColors(),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                    if (!focus.isFocused && apiKey != profile?.apiKey.orEmpty()) {
                        onApiKeyChange(apiKey)
                    }
                },
            )
            ProviderModelDropdown(
                selectedModel = profile?.model ?: initial.model,
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
                modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                    if (!focus.isFocused && baseUrl != initial.baseUrl) {
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
                        ModelDiscoveryUiState.Idle -> "Add an API key to load available models."
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
            modifier = Modifier.fillMaxWidth().menuAnchor(),
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
        androidx.compose.material3.OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Retry loading models") }
    }
}
