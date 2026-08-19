package com.hermes.agent.ui.chat.components
import com.hermes.agent.domain.settings.*

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
) {
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

    var menuExpanded by remember { mutableStateOf(false) }
    var activeArtifact by remember { mutableStateOf<CodeArtifact?>(null) }
    val artifacts = remember(message.content) {
        ArtifactExtractor.extractArtifacts(message.content)
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
                .combinedClickable(
                    onClick = { /* normal selection */ },
                    onLongClick = {
                        if (isUser) {
                            menuExpanded = true
                        }
                    },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            SelectionContainer {
                Text(
                    text = message.content,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Context menu for User Turn Rewind / Branching
            if (isUser) {
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit & Retry") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEditMessage?.invoke(message)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Retry with Ultrabrain") },
                        leadingIcon = { Icon(Icons.Outlined.Psychology, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRetryWithAlias?.invoke(message, "ultrabrain")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Retry with Quick/Local") },
                        leadingIcon = { Icon(Icons.Outlined.Bolt, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRetryWithAlias?.invoke(message, "quick")
                        },
                    )
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
