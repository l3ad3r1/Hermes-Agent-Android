package com.hermes.agent.ui.settings

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
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.data.plugin.script.ScriptPluginPermissions
import com.hermes.agent.ui.components.SlimTopBar

@Composable
fun ModulesSettingsScreen(
    onBack: () -> Unit,
    viewModel: ModulesSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            SlimTopBar(
                title = "Modules",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Modules add new tools to Hermes. They run in a sandbox and only get the " +
                    "permissions you approve.",
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = state.registryUrl,
                onValueChange = viewModel::setRegistryUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Module repository") },
                singleLine = true,
            )
            Button(
                onClick = viewModel::loadRegistry,
                enabled = !state.loading && state.registryUrl.isNotBlank(),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                } else {
                    Text("Refresh")
                }
            }

            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            if (state.installed.isNotEmpty()) {
                Text("Installed", style = MaterialTheme.typography.titleMedium)
                state.installed.forEach { module ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(module.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "v${module.version} · ${module.author.ifBlank { "unknown author" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Switch(
                                    checked = module.enabled,
                                    onCheckedChange = { viewModel.setEnabled(module.id, it) },
                                    enabled = state.busyId != module.id,
                                )
                            }
                            if (module.description.isNotBlank()) Text(module.description)
                            if (module.grantedPermissions.isNotBlank()) {
                                Text(
                                    "Permissions: ${module.grantedPermissions}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            OutlinedButton(onClick = { viewModel.uninstall(module.id) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = null)
                                Text("Remove")
                            }
                        }
                    }
                }
                HorizontalDivider()
            }

            Text("Available", style = MaterialTheme.typography.titleMedium)
            val notInstalled = state.available.filterNot { it.id in state.installedIds }
            if (notInstalled.isEmpty() && !state.loading) {
                Text(
                    if (state.available.isEmpty()) {
                        "This repository has no modules yet."
                    } else {
                        "Everything in this repository is installed."
                    },
                )
            }
            notInstalled.forEach { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(entry.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            "v${entry.version} · ${entry.author.ifBlank { "unknown author" }}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (entry.description.isNotBlank()) Text(entry.description)
                        Button(
                            onClick = { viewModel.requestInstall(entry) },
                            enabled = state.busyId == null,
                        ) {
                            if (state.busyId == entry.id) {
                                CircularProgressIndicator(Modifier.size(18.dp))
                            } else {
                                Icon(Icons.Outlined.Download, contentDescription = null)
                                Text("Install")
                            }
                        }
                    }
                }
            }
        }
    }

    // Nothing is installed until the user sees exactly what it will be allowed
    // to do and approves it.
    state.pendingInstall?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::cancelInstall,
            title = { Text("Install ${pending.manifest.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("by ${pending.manifest.author.ifBlank { "unknown author" }} · v${pending.manifest.version}")
                    if (pending.manifest.description.isNotBlank()) {
                        Text(pending.manifest.description)
                    }
                    Text("Adds these tools:", fontWeight = FontWeight.SemiBold)
                    pending.manifest.tools.forEach { tool ->
                        Text("• ${tool.name} — ${tool.description}")
                    }
                    Text("Permissions:", fontWeight = FontWeight.SemiBold)
                    if (pending.manifest.permissions.isEmpty()) {
                        Text("• None. It can only compute and return text.")
                    } else {
                        pending.manifest.permissions.forEach { permission ->
                            Text("• ${ScriptPluginPermissions.describe(permission)}")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = viewModel::confirmInstall) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelInstall) { Text("Cancel") }
            },
        )
    }
}
