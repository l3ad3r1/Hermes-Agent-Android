package com.hermes.agent.ui.evolution

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.data.plugin.evolution.EvolutionModuleInstaller
import com.hermes.agent.data.plugin.evolution.EvolutionProposal
import com.hermes.agent.data.plugin.evolution.ProposalKind
import com.hermes.agent.data.plugin.evolution.ProposalStatus
import com.hermes.agent.ui.components.SlimTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EvolutionScreen(
    onBack: () -> Unit,
    viewModel: EvolutionViewModel = hiltViewModel(),
) {
    val proposals by viewModel.proposals.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val overrides by viewModel.liveOverrides.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            SlimTopBar(
                title = "Feature evolution",
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
                "Hermes looks at how you use it, proposes improvements, and — once you approve — has one " +
                    "desktop bot build each one and another review it. Fixes that fit a sandboxed module " +
                    "install on the fly after you review them; bigger changes go to your self-repair repo " +
                    "and arrive as a test build.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StatusBanner(ui, onDismiss = viewModel::dismissMessage)

            ui.review?.let { review ->
                ModuleReviewCard(
                    review = review,
                    busy = ui.busy != null,
                    onInstall = { viewModel.install(review) },
                    onCancel = viewModel::closeReview,
                )
            }

            SettingsCard(
                weekly = settings.weeklyAnalysis,
                builder = settings.builderProfile,
                reviewer = settings.reviewerProfile,
                profiles = profiles,
                gatewayConfigured = ui.gatewayConfigured,
                lastAnalysis = settings.lastAnalysisAt.takeIf { it > 0 }?.let { at ->
                    "${formatDate(at)} — ${settings.lastAnalysisResult}"
                },
                busy = ui.busy != null,
                onWeekly = viewModel::setWeekly,
                onBuilder = viewModel::setBuilder,
                onReviewer = viewModel::setReviewer,
                onAnalyze = viewModel::analyzeNow,
            )

            if (overrides.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Live fixes", style = MaterialTheme.typography.titleMedium)
                        overrides.forEach { (tool, module) ->
                            Text("$tool → $module", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "Each reverts to the built-in tool automatically after 3 failures in a row.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (proposals.isEmpty()) {
                Text(
                    "No proposals yet. Tap \"Analyze now\" after using Hermes for a while.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            proposals.forEach { p ->
                ProposalCard(
                    proposal = p,
                    busy = ui.busy != null,
                    repairConfigured = ui.repairConfigured,
                    onApprove = { viewModel.approve(p.id) },
                    onReject = { viewModel.reject(p.id) },
                    onSend = { viewModel.sendToBots(p.id) },
                    onReview = { viewModel.openReview(p.id) },
                    onRollback = { viewModel.rollback(p.id) },
                    onFile = { viewModel.fileAppChange(p.id) },
                    onMarkInstalled = { viewModel.markInstalled(p.id) },
                )
            }
        }
    }
}

@Composable
private fun StatusBanner(ui: EvolutionViewModel.UiState, onDismiss: () -> Unit) {
    when {
        ui.busy != null -> Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(ui.busy, style = MaterialTheme.typography.bodyMedium)
            }
        }
        ui.error != null || ui.message != null -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    ui.error ?: ui.message.orEmpty(),
                    color = if (ui.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    weekly: Boolean,
    builder: String,
    reviewer: String,
    profiles: List<String>,
    gatewayConfigured: Boolean,
    lastAnalysis: String?,
    busy: Boolean,
    onWeekly: (Boolean) -> Unit,
    onBuilder: (String) -> Unit,
    onReviewer: (String) -> Unit,
    onAnalyze: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Weekly analysis", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "While charging and idle. Sends anonymised usage evidence to your cloud model; " +
                            "proposes only — nothing changes without your approval.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = weekly, onCheckedChange = onWeekly)
            }
            lastAnalysis?.let {
                Text("Last analysis: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onAnalyze, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Analyze now") }

            HorizontalDivider()
            Text("Bots", style = MaterialTheme.typography.titleMedium)
            Text(
                if (gatewayConfigured) {
                    "Profiles on your desktop Hermes gateway. One can run a local LLM, the other relay to Antigravity; " +
                        "they may be the same PC. Add profiles on the Bots screen."
                } else {
                    "No desktop gateway is connected (Settings → Connections). Module proposals wait until it is; " +
                        "app changes can still be filed to the self-repair repo."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (gatewayConfigured) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            ProfilePicker("Builder", builder, profiles, onBuilder)
            ProfilePicker("Reviewer", reviewer, profiles, onReviewer)
        }
    }
}

@Composable
private fun ProfilePicker(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label bot: ${selected.ifBlank { "not set" }}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(name); open = false })
            }
            DropdownMenuItem(text = { Text("None") }, onClick = { onSelect(""); open = false })
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: EvolutionProposal,
    busy: Boolean,
    repairConfigured: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onSend: () -> Unit,
    onReview: () -> Unit,
    onRollback: () -> Unit,
    onFile: () -> Unit,
    onMarkInstalled: () -> Unit,
) {
    var expanded by rememberSaveable(proposal.id) { mutableStateOf(false) }
    val p = proposal
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.title, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(p.kind.label) })
                AssistChip(onClick = {}, label = { Text(p.status.label) })
            }
            if (p.statusMessage.isNotBlank()) {
                Text(
                    p.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (p.status == ProposalStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            Text(p.problem, style = MaterialTheme.typography.bodyMedium)
            p.targetTool?.let { Text("Fixes: $it", style = MaterialTheme.typography.labelMedium) }

            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Details") }
            if (expanded) {
                if (p.evidence.isNotBlank()) Detail("Evidence", p.evidence)
                Detail("Acceptance criteria", p.acceptanceCriteria.joinToString("\n") { "• $it" })
                if (p.reviewFindings.isNotEmpty()) {
                    Detail("Latest findings (${p.reviewVerdict?.name ?: "phone"})", p.reviewFindings.joinToString("\n") { "• $it" })
                }
                if (p.testReport.isNotBlank()) Detail("On-device checks", p.testReport, mono = true)
                if (p.kind == ProposalKind.APP_CHANGE) p.artifact?.let { Detail("Change spec", it, mono = true) }
                p.issueUrl?.let { Detail("Issue", it) }
                Text(
                    "Updated ${formatDate(p.updatedAt)} · rounds: ${p.rounds}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val stale = (p.status == ProposalStatus.BUILDING || p.status == ProposalStatus.IN_REVIEW) &&
                    System.currentTimeMillis() - p.updatedAt > STALE_MILLIS
                when (p.status) {
                    ProposalStatus.PROPOSED -> {
                        Button(onClick = onApprove, enabled = !busy) { Text("Approve") }
                        OutlinedButton(onClick = onReject, enabled = !busy) { Text("Reject") }
                    }
                    ProposalStatus.APPROVED -> {
                        Button(onClick = onSend, enabled = !busy) { Text("Send to bots") }
                        if (p.kind == ProposalKind.APP_CHANGE && repairConfigured) {
                            OutlinedButton(onClick = onFile, enabled = !busy) { Text("File without bots") }
                        }
                        OutlinedButton(onClick = onReject, enabled = !busy) { Text("Reject") }
                    }
                    ProposalStatus.BUILDING, ProposalStatus.IN_REVIEW -> {
                        if (stale) Button(onClick = onSend, enabled = !busy) { Text("Restart") }
                        OutlinedButton(onClick = onReject, enabled = !busy) { Text("Cancel") }
                    }
                    ProposalStatus.READY -> if (p.kind.hotLoadable) {
                        Button(onClick = onReview, enabled = !busy) { Text("Review & install") }
                        OutlinedButton(onClick = onReject, enabled = !busy) { Text("Reject") }
                    } else if (p.issueUrl == null) {
                        Button(onClick = onFile, enabled = !busy && repairConfigured) {
                            Text(if (repairConfigured) "File to repair repo" else "Set up self-repair first")
                        }
                        OutlinedButton(onClick = onReject, enabled = !busy) { Text("Reject") }
                    } else {
                        Button(onClick = onMarkInstalled, enabled = !busy) { Text("Mark installed") }
                    }
                    ProposalStatus.INSTALLED -> if (p.kind.hotLoadable) {
                        OutlinedButton(onClick = onRollback, enabled = !busy) { Text("Roll back") }
                    }
                    ProposalStatus.FAILED, ProposalStatus.ROLLED_BACK -> {
                        Button(onClick = onApprove, enabled = !busy) { Text("Retry") }
                        OutlinedButton(onClick = onReject, enabled = !busy) { Text("Reject") }
                    }
                    ProposalStatus.REJECTED -> Unit
                }
            }
        }
    }
}

@Composable
private fun ModuleReviewCard(
    review: EvolutionModuleInstaller.ModuleReview,
    busy: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val m = review.manifest
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Review before installing", style = MaterialTheme.typography.titleMedium)
            Text(review.proposal.title, style = MaterialTheme.typography.bodyMedium)
            if (m != null) {
                Detail("Module", "${m.name} (${m.id}) v${m.version}")
                Detail(
                    "Permissions",
                    if (review.permissions.isEmpty()) "None — pure computation" else review.permissions.joinToString("\n") { "• ${it.second}" },
                )
                if (m.overrides.isNotEmpty()) {
                    Detail(
                        "Replaces built-in tools",
                        m.overrides.joinToString() + "\nThe built-in stays underneath and comes back automatically if this fails 3 times in a row.",
                    )
                }
                Detail("Tools", m.tools.joinToString("\n") { "• ${it.name}: ${it.description}" })
            }
            Detail("SHA-256 (pinned at install)", review.sha256, mono = true)
            if (review.proposal.reviewFindings.isNotEmpty()) {
                Detail("Reviewer notes", review.proposal.reviewFindings.joinToString("\n") { "• $it" })
            }
            Detail("On-device checks (just re-run)", (review.findings + review.testReport).joinToString("\n"), mono = true)
            Text("Code", style = MaterialTheme.typography.labelLarge)
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    (m?.main ?: review.manifestJson).take(MAX_CODE_PREVIEW),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onInstall, enabled = review.passed && !busy, modifier = Modifier.weight(1f)) {
                    Text(if (review.passed) "Install" else "Checks failed")
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun Detail(label: String, text: String, mono: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(
            text,
            style = if (mono) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(millis))

private const val STALE_MILLIS = 60L * 60 * 1000
private const val MAX_CODE_PREVIEW = 32_000
