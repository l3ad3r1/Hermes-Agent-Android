package com.hermes.agent.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Shown on the launch after a crash. The report stays on the phone unless the user shares it, and
 * the whole text is shown so they can see exactly what would be sent.
 */
@Composable
fun CrashReportDialog(report: String, onShare: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hermes crashed last time") },
        text = {
            Column {
                Text(
                    "This report was saved on your phone. It holds the app version, your device model " +
                        "and the error trace, and no chats, settings or keys. Nothing is sent unless you share it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = { TextButton(onClick = onShare) { Text("Share") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Delete") } },
    )
}
