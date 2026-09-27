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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.data.evolution.ReflectiveSkillRefiner
import com.hermes.agent.domain.skill.SkillDoc
import com.hermes.agent.ui.components.SlimTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RefineSkillScreen(
    onBack: () -> Unit,
    viewModel: RefineSkillViewModel = hiltViewModel(),
) {
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            SlimTopBar(
                title = "Refine skills from usage",
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
                "Reflects on how each skill was actually used in your recent chats and " +
                    "proposes an improved version — both the instructions and the " +
                    "description that decides when the skill gets loaded. Nothing changes " +
                    "until you approve it, and every version is kept so you can roll back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val s = state) {
                is RefineUiState.Proposal -> ProposalCard(
                    proposal = s.proposal,
                    onApply = { viewModel.apply(s.proposal) },
                    onDiscard = viewModel::reset,
                )
                is RefineUiState.Running -> StatusCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Refining ${s.skillName}…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is RefineUiState.Applied -> StatusCard {
                    Text("✓ Skill updated.", color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
                is RefineUiState.Restored -> StatusCard {
                    Text("✓ Restored v${s.version}.", color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
                is RefineUiState.NoChange -> StatusCard {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) { Text("OK") }
                }
                is RefineUiState.Error -> StatusCard {
                    Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) { Text("Dismiss") }
                }
                is RefineUiState.Idle -> Unit
            }

            history?.let { h ->
                HistoryCard(
                    history = h,
                    onRestore = viewModel::restore,
                    onClose = viewModel::closeHistory,
                )
            }

            if (state is RefineUiState.Idle && history == null) {
                if (skills.isEmpty()) {
                    Text(
                        "No user-created skills yet. Skills are auto-created as the agent " +
                            "completes multi-tool tasks; built-in skills can't be refined.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    for (skill in skills) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(skill.name, style = MaterialTheme.typography.titleMedium)
                                if (skill.description.isNotBlank()) {
                                    Text(
                                        skill.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { viewModel.refine(skill.name) },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Refine from usage") }
                                    OutlinedButton(
                                        onClick = { viewModel.showHistory(skill.name) },
                                    ) { Text("History") }
                                }
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
    proposal: ReflectiveSkillRefiner.Proposal,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
) {
    val originalBody = SkillDoc.extractBody(proposal.originalContent)
    val proposedBody = SkillDoc.extractBody(proposal.proposedContent)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(proposal.skillName, style = MaterialTheme.typography.titleMedium)
            Text(
                proposal.rationale,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Based on ${proposal.traceCount} usage trace(s) · " +
                    "${originalBody.length} → ${proposedBody.length} chars",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Constraint gates
            for (c in proposal.constraints) {
                val icon = if (c.passed) "✓" else "✗"
                val color = if (c.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                Text("$icon ${c.name}: ${c.message}", style = MaterialTheme.typography.labelSmall, color = color)
            }

            // The description decides whether this skill is ever retrieved, so
            // a change to it deserves as much scrutiny as the body.
            if (proposal.descriptionChanged) {
                HorizontalDivider()
                Text("Description (drives when this skill loads):", style = MaterialTheme.typography.labelLarge)
                Text(
                    proposal.originalDescription,
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDecoration = TextDecoration.LineThrough,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    proposal.proposedDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (proposal.bodyChanged) {
                HorizontalDivider()
                Text("Proposed body:", style = MaterialTheme.typography.labelLarge)
                Text(
                    proposedBody,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
    history: HistoryState,
    onRestore: (RevisionRow) -> Unit,
    onClose: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${history.skillName} — version history", style = MaterialTheme.typography.titleMedium)

            when {
                history.loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Loading…", style = MaterialTheme.typography.bodySmall)
                }

                history.revisions.isEmpty() -> Text(
                    "No earlier versions yet. A snapshot is kept every time this skill " +
                        "is refined, improved, or edited.",
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
                        if (rev.description.isNotBlank()) {
                            Text(
                                "Description: ${rev.description}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onRestore(rev) }) { Text("Restore this version") }
                    }
                }
            }

            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}
