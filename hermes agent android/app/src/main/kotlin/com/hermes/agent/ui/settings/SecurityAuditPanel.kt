package com.hermes.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Pending
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hermes.agent.util.audit.ControlStatus
import com.hermes.agent.util.audit.SecurityAudit
import com.hermes.agent.util.audit.SecurityControl

/**
 * Phase 4 Settings panel — security audit checklist.
 *
 * Renders the [SecurityAudit] controls with status icons so users (and
 * reviewers) can see at a glance which security features are enforced,
 * partial, or pending. Surfaces the per-control description as a
 * subtitle.
 */
@Composable
fun SecurityAuditPanel(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Security audit",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "${SecurityAudit.enforcedCount}/${SecurityAudit.all.size} enforced",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        // NOTE: must be a plain Column, not a LazyColumn. This panel is hosted
        // inside SettingsScreen's Column(Modifier.verticalScroll(...)), and a
        // LazyColumn nested in a vertically-scrollable parent is measured with an
        // infinite max-height constraint, which Compose throws on at render time.
        // The control list is small and fixed, so a Column with forEach is correct.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecurityAudit.all.forEach { control ->
                    SecurityControlRow(control)
                }
            }
        }
    }
}

@Composable
private fun SecurityControlRow(control: SecurityControl) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val (icon, tint) = when (control.status) {
            ControlStatus.ENFORCED -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
            ControlStatus.PARTIAL -> Icons.Outlined.Warning to MaterialTheme.colorScheme.error
            ControlStatus.PENDING -> Icons.Outlined.Pending to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(icon, contentDescription = null, tint = tint)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = control.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.col