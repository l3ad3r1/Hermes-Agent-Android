package com.hermes.agent.ui.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.hermes.agent.R

/** Rounded, reference-style composer with quick actions, text, voice, and send controls. */
@Composable
fun ChatInputBar(
    isSending: Boolean,
    isListening: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onMicToggle: () -> Unit,
    modifier: Modifier = Modifier,
    prefillText: String = "",
) {
    var text by remember(prefillText) { mutableStateOf(prefillText) }
    var quickActionsOpen by remember { mutableStateOf(false) }

    fun submit() {
        val message = text.trim()
        if (message.isNotEmpty()) {
            onSend(message)
            text = ""
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                IconButton(
                    onClick = { quickActionsOpen = true },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Quick actions")
                }
                DropdownMenu(
                    expanded = quickActionsOpen,
                    onDismissRequest = { quickActionsOpen = false },
                ) {
                    listOf(
                        "Plan my day" to "Help me plan my day",
                        "Create a note" to "Create a note for me",
                        "Look something up" to "Look something up for me",
                    ).forEach { (label, prompt) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                text = prompt
                                quickActionsOpen = false
                            },
                        )
                    }
                }
            }

            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = 144.dp)
                    .padding(horizontal = 8.dp, vertical = 13.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                maxLines = 6,
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )

            IconButton(
                onClick = onMicToggle,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Mic,
                    contentDescription = stringResource(R.string.a11y_voice_input),
                    tint = if (isListening) {
                        MaterialTheme.colorScheme.error
                    } else MaterialTheme.colorScheme.onSurface,
                )
            }

            val actionColor = if (isSending) {
                MaterialTheme.colorScheme.error
            } else MaterialTheme.colorScheme.primary
            Surface(
                onClick = when {
                    isSending -> onCancel
                    text.isNotBlank() -> ::submit
                    else -> onMicToggle
                },
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(24.dp),
                color = actionColor,
                contentColor = if (isSending) {
                    MaterialTheme.colorScheme.onError
                } else MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = when {
                            isSending -> Icons.Outlined.Stop
                            text.isNotBlank() -> Icons.Outlined.ArrowUpward
                            else -> Icons.Outlined.GraphicEq
                        },
                        contentDescription = when {
                            isSending -> stringResource(R.string.a11y_stop_generating)
                            text.isNotBlank() -> stringResource(R.string.a11y_send_button)
                            else -> stringResource(R.string.a11y_voice_input)
                        },
                        modifier = Modifier.size(25.dp),
                    )
                }
            }
        }
    }
}
