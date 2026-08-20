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
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.ui.components.SlimTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesSettingsScreen(onBack: () -> Unit, viewModel: ModulesSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { SlimTopBar(title = "Modules", navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Back") } }) }) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Download modules from the shared public repository. The catalog and APK are verified before they are saved.")
            OutlinedTextField(value = state.catalogUrl, onValueChange = viewModel::setCatalogUrl, modifier = Modifier.fillMaxWidth(), label = { Text("Module repository URL") }, placeholder = { Text("https://example.com/plugins/catalog.json") }, singleLine = true)
            Button(onClick = viewModel::loadCatalog, enabled = !state.loading && state.catalogUrl.isNotBlank()) { if (state.loading) CircularProgressIndicator() else Text("Load modules") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val catalog = state.catalog
            catalog?.plugins?.forEach { entry ->
                val key = "${entry.manifest.id}:${entry.manifest.versionCode}"
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.manifest.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("${entry.manifest.author} · v${entry.manifest.versionName}")
                    Text(entry.manifest.capabilities.joinToString { it.description })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { viewModel.download(entry) }, enabled = state.downloadingId == null) { Icon(Icons.Outlined.Download, contentDescription = null); Text(if (state.downloadingId == key) "Downloading…" else "Download") }; state.downloaded[key]?.let { Text("Saved (${it.sizeBytes} bytes)") } }
                } }
            }
            if (catalog != null && catalog.plugins.isEmpty()) Text("This repository has no modules yet.")
        }
    }
}
