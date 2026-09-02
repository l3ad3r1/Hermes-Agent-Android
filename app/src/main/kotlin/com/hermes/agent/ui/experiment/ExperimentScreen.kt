package com.hermes.agent.ui.experiment
import com.hermes.agent.domain.settings.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExperimentScreen(
    onBack: () -> Unit = {},
    viewModel: ExperimentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    val quickPresets = listOf("gpt-4o-mini", "claude-3-5-sonnet", "deepseek-chat", "local")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model A/B Benchmark") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back",
                        )
                    }
                },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Benchmark and compare two models side-by-side with live latency (TTFT), speed (tok/s), and generation metrics.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.prompt,
                onValueChange = viewModel::setPrompt,
                label = { Text("Benchmark Prompt") },
                placeholder = { Text("Write a quick python script to calculate fibonacci numbers...") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                maxLines = 6,
            )

            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = state.modelA,
                    onValueChange = viewModel::setModelA,
                    label = { Text("Model A") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    quickPresets.forEach { preset ->
                        SuggestionChip(
                            onClick = { viewModel.setModelA(preset) },
                            label = { Text(preset) },
                        )
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = state.modelB,
                    onValueChange = viewModel::setModelB,
                    label = { Text("Model B") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    quickPresets.forEach { preset ->
                        SuggestionChip(
                            onClick = { viewModel.setModelB(preset) },
                            label = { Text(preset) },
                        )
                    }
                }
            }

            Button(
                onClick = viewModel::run,
                enabled = state.prompt.isNotBlank() && !state.isRunningA && !state.isRunningB,
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Run Benchmark")
            }

            if (state.responseA.isNotBlank() || state.responseB.isNotBlank() ||
                state.isRunningA || state.isRunningB ||
                state.errorA != null || state.errorB != null
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BenchmarkResponsePanel(
                        label = state.modelA,
                        response = state.responseA,
                        metrics = state.metricsA,
                        isRunning = state.isRunningA,
                        error = state.errorA,
                        modifier = Modifier.weight(1f),
                    )
                    BenchmarkResponsePanel(
                        label = state.modelB,
                        response = state.responseB,
                        metrics = state.metricsB,
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
private fun BenchmarkResponsePanel(
    label: String,
    response: String,
    metrics: ModelBenchmarkMetrics,
    isRunning: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                )
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }

            // Metrics Bar
            if (metrics.tokenCount > 0) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.surface)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(12.dp), tint = scheme.primary)
                            Spacer(Modifier.width(2.dp))
                            Text("${metrics.ttftMs}ms TTFT", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Speed, contentDescription = null, modifier = Modifier.size(12.dp), tint = scheme.secondary)
                            Spacer(Modifier.width(2.dp))
                            Text("${"%.1f".format(metrics.tokensPerSec)} tok/s", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(12.dp), tint = scheme.outline)
                            Spacer(Modifier.width(2.dp))
                            Text("${"%.2f".format(metrics.totalTimeMs / 1000.0)}s total", fontSize = 11.sp, color = scheme.onSurfaceVariant)
                        }
                        Text("${metrics.tokenCount} tokens", fontSize = 11.sp, color = scheme.onSurfaceVariant)
                    }
                }
            }

            when {
                error != null -> Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                response.isBlank() && isRunning -> Text(
                    text = "Streaming response…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    text = response,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
