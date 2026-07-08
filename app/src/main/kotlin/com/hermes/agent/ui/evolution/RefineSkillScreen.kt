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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.data.evolution.ReflectiveSkillRefiner
import com.hermes.agent.domain.skill.SkillConstraints
import com.hermes.agent.domain.skill.SkillDoc
import com.hermes.agent.ui.components.SlimTopBar

@Composable
fun RefineSkillScreen(
    onBack: () -> Unit,
    viewModel: RefineSkillViewModel = hiltViewModel(),
) {
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

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
                    "proposes an improved version. Nothing changes until you approve it.",
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

            if (state is RefineUiState.Idle) {
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
                                Button(
                                    onClick = { viewModel.refine(skill.name) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Refine from usage") }
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

            Text("Proposed body:", style = MaterialTheme.typography.labelLarge)
            Text(
                proposedBody,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
