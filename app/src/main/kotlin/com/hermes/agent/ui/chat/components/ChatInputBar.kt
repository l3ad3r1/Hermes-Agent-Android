package com.hermes.agent.ui.chat.components
import com.hermes.agent.domain.settings.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
    onSendWithAttachment: ((String, String?, String?) -> Unit)? = null,
    reasoningEffort: String = "medium",
    onReasoningEffortChange: ((String) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by remember(prefillText) { mutableStateOf(prefillText) }
    var attachedImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var quickActionsOpen by remember { mutableStateOf(false) }
    var effortMenuOpen by remember { mutableStateOf(false) }
    val listeningDescription = stringResource(R.string.a11y_listening)
    val endVoiceChatDescription = stringResource(R.string.a11y_end_voice_chat)

    val imagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri: android.net.Uri? ->
        attachedImageUri = uri
    }

    fun submit() {
        val message = text.trim()
        if (message.isNotEmpty() || attachedImageUri != null) {
            val uriStr = attachedImageUri?.toString()
            val mime = attachedImageUri?.let { context.contentResolver.getType(it) }
            if (onSendWithAttachment != null) {
                onSendWithAttachment(message, uriStr, mime)
            } else {
                onSend(message)
            }
            text = ""
            attachedImageUri = null
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        SlashCommandPalette(
            currentQuery = text,
            onSelectCommand = { cmd ->
                text = cmd.template
            },
        )

        attachedImageUri?.let { uri ->
            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "Image attached",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "✕",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clickable { attachedImageUri = null }
                        .padding(horizontal = 4.dp),
                )
            }
        }

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
                    DropdownMenuItem(
                        text = { Text("Attach image") },
                        onClick = {
                            quickActionsOpen = false
                            imagePickerLauncher.launch("image/*")
                        },
                    )
                    if (onReasoningEffortChange != null) {
                        DropdownMenuItem(
                            text = { Text("Reasoning effort: ${reasoningEffort.replaceFirstChar { it.uppercase() }}") },
                            onClick = { quickActionsOpen = false; effortMenuOpen = true },
                        )
                    }
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
                DropdownMenu(
                    expanded = effortMenuOpen,
                    onDismissRequest = { effortMenuOpen = false },
                ) {
                    listOf("minimal", "low", "medium", "high").forEach { level ->
                        DropdownMenuItem(
                            text = { Text(level.replaceFirstChar { it.uppercase() } + if (level == reasoningEffort) "  ✓" else "") },
                            onClick = {
                                onReasoningEffortChange?.invoke(level)
                                effortMenuOpen = false
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
