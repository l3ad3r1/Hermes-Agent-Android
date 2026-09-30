package com.hermes.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.agent.data.hotfix.HotfixEvent
import com.hermes.agent.data.hotfix.HotfixStatus

/**
 * Hot-fix status under Settings → Updates: the active patch, a patch waiting for a restart,
 * the last outcome (including automatic rollbacks), and "Remove patch".
 */
@Composable
internal fun HotfixStatusCard(
    status: HotfixStatus,
    onRestart: () -> Unit,
    onRemove: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Outlined.Healing,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text("Hot-fixes", style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                "Small fixes can arrive as a patch instead of a full update: no reinstall, but Hermes " +
                    "has to restart to use it. A patch is only accepted if it is signed with the same key " +
                    "as this app and was built for exactly this version.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Build: ${status.baseTinkerId ?: "unknown"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!status.available) {
                Text(
                    status.unavailableReason ?: "Hot-fixes are unavailable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val active = status.active
            if (active != null) {
                Text(
                    if (active.patchVersion > 0) "Fix #${active.patchVersion} is active." else "A fix is active.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (active.notes.isNotBlank()) {
                    Text(active.notes, style = MaterialTheme.typography.bodySmall)
                }
            } else if (status.available && status.staged == null) {
                Text("No fix applied — Hermes is running the installed version.", style = MaterialTheme.typography.bodySmall)
            }

            val staged = status.staged
            if (status.busy) {
                val p = status.progressPercent
                Text("Downloading and checking the fix…", style = MaterialTheme.typography.bodySmall)
                if (p != null) LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (staged != null && !staged.installed) {
                Text("Preparing fix #${staged.patchVersion}…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            status.lastEvent?.let { event ->
                val isProblem = event.kind in setOf(
                    HotfixEvent.Kind.REJECTED, HotfixEvent.Kind.INSTALL_FAILED,
                    HotfixEvent.Kind.LOAD_FAILED, HotfixEvent.Kind.ROLLED_BACK,
                )
                // "Loaded"/"Staged" duplicate the lines above; show the rest.
                if (event.kind != HotfixEvent.Kind.LOADED && event.kind != HotfixEvent.Kind.STAGED) {
                    Text(
                        event.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (status.restartPending) {
                Text(
                    "Restart Hermes to finish. Anything running (a reply, a download) is stopped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { confirmRestart = true }, modifier = Modifier.fillMaxWidth()) { Text("Restart now") }
            }
            if (status.available && (active != null || staged != null) && !status.busy) {
                OutlinedButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth()) { Text("Remove patch") }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the patch?") },
            text = { Text("Hermes goes back to the installed version after its next restart. The same fix will not be offered again.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text("Restart Hermes now?") },
            text = { Text("Hermes closes and opens again. Any reply in progress is stopped.") },
            confirmButton = { TextButton(onClick = { confirmRestart = false; onRestart() }) { Text("Restart") } },
            dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("Later") } },
        )
    }
}
