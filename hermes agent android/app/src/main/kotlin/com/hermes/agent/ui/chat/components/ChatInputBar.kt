package com.hermes.agent.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.hermes.agent.R

/**
 * Bottom input bar. Phase 3 adds:
 *   - Mic button (left of text field) for voice input.
 *   - S Pen toggle button (left of mic) for handwriting capture.
 *
 * Calls [onSend] when the user taps the send button or presses the
 * Send IME action. Calls [onCancel] when the assistant is currently
 * streaming and the user taps the stop button.
 */
@Composable
fun ChatInputBar(
    isSending: Boolean,
    isListening: Boolean,
    isSPenMode: Boolean,
    sPenAvailable: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onMicToggle: () -> Unit,
    onSPenToggle: () -> Unit,
    modifier: Modifier = Modifier,
    prefillText: String = "",
) {
    var text by remember(prefillText) { mutableStateOf(prefillText) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // S Pen toggle — only shown when the S Pen SDK is available.
        if (sPenAvailable) {
            IconButton(
                onClick = onSPenToggle,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Toggle S Pen input",
                    tint = if (isSPenMode) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                )
            }
        }

        // Mic button.
        IconButton(
            onClick = onMicToggle,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Mic,
                contentDescription = "Voice input",
                tint = if (isListening) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                },
            )
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp, max = 160.dp),
            placeholder = { Text(stringResource(R.string.chat_placeholder)) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send,
            ),
            shape = MaterialTheme.shapes.large,
            textStyle = MaterialTheme.typography.bodyLarge,
            maxLines = 6,
        )

        if (isSending) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Stop,
                    contentDescription = "Stop generating",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            val canSend = text.isNotBlank()
            IconButton(
                onClick = {
                    if (canSend) {
                        onSend(text)
                        text = ""
                    }
                },
                enabled = canSend,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Send,
                    contentDescription = stringResource(R.string.chat_send),
                    tint = if (canSend) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                )
            }
        }
    }
}
