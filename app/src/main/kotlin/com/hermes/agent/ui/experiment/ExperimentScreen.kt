package com.hermes.agent.ui.experiment

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentScreen(viewModel: ExperimentViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Experiment") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Compare two models on the same prompt",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = state.prompt,
                onValueChange = viewModel::setPrompt,
                label = { Text("Prompt") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                maxLines = 6,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.modelA,
                    onValueChange = viewModel::setModelA,
                    label = { Text("Model A") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.modelB,
                    onValueChange = viewModel::setModelB,
                    label = { Text("Model B") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Button(
                onClick = viewModel::run,
                enabled = state.prompt.isNotBlank() && !state.isRunningA && !state.isRunningB,
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Run")
            }

            if (state.responseA.isNotBlank() || state.responseB.isNotBlank() ||
                state.isRunningA || state.isRunningB ||
                state.errorA != null || state.errorB != null) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ResponsePanel(
                        label = "Model A — ${state.modelA}",
                        response = state.responseA,
                        isRunning = state.isRunningA,
                        error = state.errorA,
                        modifier = Modifier.weight(1f),
                    )
                    ResponsePanel(
                        label = "Model B — ${state.modelB}",
                        response = state.responseB,
                        isRunning = state.isRunningB,
                        error = state.errorB,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResponsePanel(
    label: String,
    response: String,
    isRunning: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                if (isRunning) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
            when {
                error != null -> Text(error, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                response.isBlank() && isRunning -> Text("…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text(response,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
