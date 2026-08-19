package com.hermes.agent.ui.evolution

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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.data.evolution.ReflectivePromptRefiner
import com.hermes.agent.ui.components.SlimTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RefinePromptScreen(
    onBack: () -> Unit,
    viewModel: RefinePromptViewModel = hiltViewModel(),
) {
    val roles by viewModel.roles.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            SlimTopBar(
                title = "Agent operating notes",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Each agent has a fixed built-in prompt that can't be changed, plus " +
                    "learned notes that can. Hermes reflects on how an agent actually " +
                    "performed and proposes updated notes — you approve them, and every " +
                    "version is kept so you can roll back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val s = state) {
                is PromptUiState.Proposal -> ProposalCard(
                    proposal = s.proposal,
                    onApply = { viewModel.apply(s.proposal) },
                    onDiscard = viewModel::reset,
                )
                is PromptUiState.Running -> StatusCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            "Reflecting on ${s.role.displayName}…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is PromptUiState.Applied -> StatusCard {
                    Text("✓ Notes updated.", color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
                is PromptUiState.Restored -> StatusCard {
                    Text("✓ Restored v${s.version}.", color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
                is PromptUiState.NoChange -> StatusCard {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) { Text("OK") }
                }
                is PromptUiState.Error -> StatusCard {
                    Text(
                        s.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) { Text("Dismiss") }
                }
                is PromptUiState.Idle -> Unit
            }

            history?.let { h ->
                HistoryCard(
                    history = h,
                    onRestore = viewModel::restore,
                    onClose = viewModel::closeHistory,
                )
            }

            if (state is PromptUiState.Idle && history == null) {
                for (row in roles) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(row.role.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                row.role.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (row.content.isBlank()) {
                                Text(
                                    "No learned notes yet — this agent runs on its built-in prompt alone.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    "Current notes (v${row.version}):",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(
                                    row.content,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.refine(row.role) },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Refine from usage") }
                                OutlinedButton(onClick = { viewModel.showHistory(row.role) }) {
                                    Text("History")
                                }
                            }
                            if (row.content.isNotBlank()) {
                                TextButton(onClick = { viewModel.clear(row.role) }) { Text("Clear notes") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}

@Composable
private fun ProposalCard(
    proposal: ReflectivePromptRefiner.Proposal,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(proposal.role.displayName, style = MaterialTheme.typography.titleMedium)
            Text(proposal.rationale, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Based on ${proposal.traceCount} trace(s) · " +
                    "${proposal.originalContent.length} → ${proposal.proposedContent.length} chars",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            for (c in proposal.constraints) {
                val icon = if (c.passed) "✓" else "✗"
                val color = if (c.passed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
                Text(
                    "$icon ${c.name}: ${c.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
            }

            if (proposal.originalContent.isNotBlank()) {
                HorizontalDivider()
                Text("Replacing:", style = MaterialTheme.typography.labelLarge)
                Text(
                    proposal.originalContent,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            Text("Proposed notes:", style = MaterialTheme.typography.labelLarge)
            Text(
                proposal.proposedContent,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onApply,
                    enabled = proposal.constraintsPass,
                    modifier = Modifier.weight(1f),
                ) { Text(if (proposal.constraintsPass) "Apply" else "Failed gates") }
                OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) { Text("Discard") }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    history: PromptHistory,
    onRestore: (PromptRevisionOption) -> Unit,
    onClose: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "${history.role.displayName} — notes history",
                style = MaterialTheme.typography.titleMedium,
            )

            when {
                history.loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Loading…", style = MaterialTheme.typography.bodySmall)
                }

                history.revisions.isEmpty() -> Text(
                    "No earlier versions yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> for (rev in history.revisions) {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "v${rev.version} · ${dateFmt.format(Date(rev.replacedAt))}",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            rev.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            rev.content.take(300),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { onRestore(rev) }) { Text("Restore this version") }
                    }
                }
            }

            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}
