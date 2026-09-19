package com.hermes.agent.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermes.agent.data.remote.RemoteJob

/** One PC cron job (bot), with its status/history and pause/resume/run actions. Shared by the
 * Bots screen's per-profile list and the global CRON screen's cross-profile roundup. */
@Composable
fun RemoteJobCard(job: RemoteJob, busy: Boolean, onAction: (String) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(job.name.ifBlank { job.id }, fontWeight = FontWeight.SemiBold)
            Text(
                buildString {
                    append(if (job.enabled) job.state.ifBlank { "active" } else "paused")
                    if (job.schedule.isNotBlank()) append("  ·  ").append(job.schedule)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            job.nextRunAt?.let {
                Text("Next $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            job.lastRunAt?.let {
                Text(
                    "Last $it ${job.lastStatus.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            job.lastError?.let {
                Text(it.take(300), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAction(if (job.enabled) "pause" else "resume") }, enabled = !busy) {
                    Text(if (job.enabled) "Pause" else "Resume")
                }
                OutlinedButton(onClick = { onAction("run") }, enabled = !busy) { Text("Run now") }
            }
        }
    }
}
