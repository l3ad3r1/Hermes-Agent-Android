package com.hermes.agent.ui.chat.components
import com.hermes.agent.domain.settings.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermes.agent.R
import com.hermes.agent.core.theme.HermesPalette

/** Rounded, reference-style composer with quick actions, text, voice, and send controls. */
@Composable
fun ChatInputBar(
    isSending: Boolean,
    isListening: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onMicToggle: () -> Unit,
    onVoiceChatToggle: () -> Unit,
    modifier: Modifier = Modifier,
    prefillText: String = "",
    voiceChatActive: Boolean = false,
) {
    var text by remember(prefillText) { mutableStateOf(prefillText) }
    var quickActionsOpen by remember { mutableStateOf(false) }
    val listeningDescription = stringResource(R.string.a11y_listening)
    val endVoiceChatDescription = stringResource(R.string.a11y_end_voice_chat)

    fun submit() {
        val message = text.trim()
        if (message.isNotEmpty()) {
            onSend(message)
            text = ""
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        SlashCommandPalette(
            currentQuery = text,
            onSelectCommand = { cmd ->
                text = cmd.template
            },
        )

        Surface(
            modifier = Modifier
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
                // While the mic is hot the icon becomes the orb, so voice
                // capture reads as the same "Hermes is busy" language as the
                // chat bubble. The button keeps its action and description, so
                // tapping still stops listening.
                //
                // Not during voice chat, though: that mode is listening too, so
                // both buttons lit up and sat side by side showing two orbs for
                // one state. In voice chat the round button is the indicator.
                if (isListening && !voiceChatActive) {
                    ThinkingOrb(
                        diameter = 26.dp,
                        listening = true,
                        modifier = Modifier.semantics {
                            contentDescription = listeningDescription
                        },
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = stringResource(R.string.a11y_voice_input),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Stop is red; everything else stays monochrome. See
            // HermesPalette.Stop for why this one control breaks the palette.
            val actionColor = when {
                isSending -> HermesPalette.Stop
                voiceChatActive -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            Surface(
                onClick = when {
                    isSending -> onCancel
                    text.isNotBlank() -> ::submit
                    else -> onVoiceChatToggle
                },
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(24.dp),
                color = actionColor,
                contentColor = when {
                    isSending -> HermesPalette.OnStop
                    voiceChatActive -> MaterialTheme.colorScheme.onError
                    else -> MaterialTheme.colorScheme.onPrimary
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // In voice chat the button becomes the orb. The theme's
                    // "error" colour is white, identical to the sending state,
                    // so colour alone could not distinguish the two — and a
                    // conversation in progress is exactly what the orb means
                    // everywhere else in the app.
                    if (voiceChatActive) {
                        // Breathes while the microphone is open and works the
                        // puzzle while Hermes is thinking or speaking, so the
                        // button says whose turn it is.
                        ThinkingOrb(
                            diameter = 30.dp,
                            color = MaterialTheme.colorScheme.onError,
                            listening = isListening,
                            modifier = Modifier.semantics {
                                contentDescription = endVoiceChatDescription
                            },
                        )
                    } else {
                    Icon(
                        imageVector = when {
                            isSending -> Icons.Outlined.Stop
                            text.isNotBlank() -> Icons.Outlined.ArrowUpward
                            else -> Icons.Outlined.GraphicEq
                        },
                        contentDescription = when {
                            isSending -> stringResource(R.string.a11y_stop_generating)
                            text.isNotBlank() -> stringResource(R.string.a11y_send_button)
                            else -> stringResource(R.string.a11y_start_voice_chat)
                        },
                        modifier = Modifier.size(25.dp),
                    )
                    }
                }
            }
        }
    }
}
}
