package com.hermes.agent.ui.settings
import com.hermes.agent.domain.settings.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Pending
import androidx.compose.material.icons.outlined.Warning
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

/** Short "N/M enforced" summary for the security-audit header. */
val securityAuditSummary: String
    get() = "${SecurityAudit.enforcedCount}/${SecurityAudit.all.size} controls enforced"

/**
 * The security-audit checklist rows, with status icons so users (and reviewers)
 * can see which controls are enforced, partial, or pending. Meant to be dropped
 * inside an [ExpandableCard] body — it draws no card or header of its own.
 *
 * NOTE: a plain Column, never a LazyColumn — this is hosted inside a parent
 * `Modifier.verticalScroll(...)`, and a nested LazyColumn is measured with an
 * infinite max-height constraint, which Compose throws on. The list is small
 * and fixed, so `forEach` is correct.
 */
@Composable
fun SecurityAuditRows(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SecurityAudit.all.forEach { control -> SecurityControlRow(control) }
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
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = control.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private typealias Warning = Color
