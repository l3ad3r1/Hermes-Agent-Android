package com.hermes.agent.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.agent.ui.chat.ToolCallStatus
import com.hermes.agent.ui.chat.ToolCallSummary

/**
 * A single tool-call card rendered inline with the streaming bubble.
 *
 * Shows the tool name, the arguments preview, the current status, and —
 * once the tool has finished — a truncated output preview. The card is
 * deliberately compact so multiple tool calls fit vertically inside the
 * streaming bubble.
 */
@Composable
fun ToolCallCard(
    summary: ToolCallSummary,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = when (summary.status) {
                ToolCallStatus.RUNNING -> Icons.Outlined.HourglassEmpty
                ToolCallStatus.SUCCEEDED -> Icons.Outlined.Check
                ToolCallStatus.FAILED -> Icons.Outlined.Error
                ToolCallStatus.PENDING -> Icons.Outlined.HourglassEmpty
            },
            contentDescription = null,
            tint = when (summary.status) {
                ToolCallStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
                ToolCallStatus.FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(modifier = Modifier.widthIn(max = 280.dp)) {
            Text(
                text = summary.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (summary.argumentsPreview.isNotBlank()) {
                Text(
                    text = summary.argumentsPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            summary.outputPreview?.let { out ->
                Text(
                    text = out,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
