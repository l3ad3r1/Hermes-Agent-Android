package com.hermes.agent.ui.chat.components
import com.hermes.agent.domain.settings.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.ui.chat.ChatListItem

/**
 * A single chat bubble with Evidence State badges, Artifact Preview actions,
 * and Long-Press Turn Rewind / Branching ("Edit & Retry").
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    onEditMessage: ((Message) -> Unit)? = null,
    onRetryWithAlias: ((Message, String) -> Unit)? = null,
    onRewindTo: ((Message) -> Unit)? = null,
    onForkFrom: ((Message) -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val isUser = message.role == MessageRole.USER
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    var actionsVisible by remember { mutableStateOf(false) }
    var confirmRewind by remember { mutableStateOf(false) }
    var activeArtifact by remember { mutableStateOf<CodeArtifact?>(null) }
    val artifacts = remember(message.content) {
        ArtifactExtractor.extractArtifacts(message.content)
    }

    if (confirmRewind) {
        // Rewind throws away messages, so it is the one action here that asks
        // first. Fork is the non-destructive alternative, named in the copy.
        AlertDialog(
            onDismissRequest = { confirmRewind = false },
            title = { Text("Rewind to here?") },
            text = {
                Text(
                    "This deletes this message and everything after it. " +
                        "To keep the original, use \"Fork from here\" instead.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRewind = false
                    onRewindTo?.invoke(message)
                }) { Text("Rewind") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRewind = false }) { Text("Cancel") }
            },
        )
    }

    activeArtifact?.let { art ->
        ArtifactPreviewBottomSheet(
            artifact = art,
            onDismiss = { activeArtifact = null },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${if (isUser) "You" else "Assistant"} said: ${message.content}"
            },
        horizontalAlignment = alignment,
    ) {
        val role = message.agentRole
        if (!isUser && role != null) {
            AgentRoleBadge(
                role = role,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }


        message.evidenceState?.let { evidence ->
            EvidenceStateBadge(
                state = evidence,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                    )
                )
                .background(bubbleColor)
                .clickable {
                    // Tap reveals the actions for this message and hides them
                    // again, so the transcript stays clean until you reach for
                    // something. Long-press is left to text selection.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    actionsVisible = !actionsVisible
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            SelectionContainer {
                Text(
                    text = message.content,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

        }

        // Actions live under the bubble rather than in a dropdown, so what is
        // available is visible at a glance and reachable with one thumb. They
        // stay hidden until the message is tapped to keep the transcript quiet.
        AnimatedVisibility(
            visible = actionsVisible,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var copied by remember { mutableStateOf(false) }
                LaunchedEffect(copied) {
                    if (copied) {
                        kotlinx.coroutines.delay(1200)
                        copied = false
                    }
                }
                MessageAction(
                    // Confirming the copy on the icon itself avoids a snackbar
                    // for something this small.
                    icon = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    label = if (copied) "Copied" else "Copy text",
                ) {
                    clipboard.setText(AnnotatedString(message.content))
                    copied = true
                }
                MessageAction(Icons.Outlined.CallSplit, "Fork a new chat from here") {
                    actionsVisible = false
                    onForkFrom?.invoke(message)
                }
                MessageAction(Icons.Outlined.History, "Rewind to here") {
                    confirmRewind = true
                }
                if (isUser) {
                    MessageAction(Icons.Outlined.Edit, "Edit and retry") {
                        actionsVisible = false
                        onEditMessage?.invoke(message)
                    }
                    MessageAction(Icons.Outlined.Psychology, "Retry with Ultrabrain") {
                        actionsVisible = false
                        onRetryWithAlias?.invoke(message, "ultrabrain")
                    }
                    MessageAction(Icons.Outlined.Bolt, "Retry with Quick/Local") {
                        actionsVisible = false
                        onRetryWithAlias?.invoke(message, "quick")
                    }
                }
            }
        }

        // Interactive Artifact Preview Chips
        if (artifacts.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .widthIn(max = 320.dp),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            ) {
                artifacts.forEach { art ->
                    AssistChip(
                        onClick = { activeArtifact = art },
                        label = { Text(art.title, style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (art.language in listOf("html", "svg")) Icons.Outlined.Visibility else Icons.Outlined.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.padding(end = 4.dp, bottom = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Streaming variant of [MessageBubble] — renders a partial reply plus a
 * typing indicator while tokens are still arriving. Also renders any
 * tool-call cards the orchestrator has emitted during the current turn
 * (Phase 2).
 */
@Composable
fun StreamingBubble(
    item: ChatListItem.StreamingItem,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = if (item.text.isBlank()) "Assistant is typing" else "Assistant is responding: ${item.text}"
            },
        horizontalAlignment = Alignment.Start,
    ) {
        item.agentRole?.let { role ->
            AgentRoleBadge(
                role = role,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomEnd = 16.dp,
                        bottomStart = 4.dp,
                    )
                )
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            // Before the first token lands there is nothing to show, and an
            // empty bubble reads as a broken turn. The orb is the app's
            // "Hermes is working" language everywhere else, so it stands in
            // until real text arrives.
            if (item.text.isBlank()) {
                ThinkingOrb(diameter = 28.dp)
            } else {
                SelectionContainer {
                    Text(
                        text = item.text,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * One message action: a small icon whose meaning is available on demand.
 *
 * The icon alone is ambiguous, so it carries both a tooltip (long-press, the
 * platform gesture for "what is this?") and a contentDescription, which is the
 * same string — screen-reader users and sighted users get the identical label
 * instead of one being an afterthought.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
