package com.hermes.agent.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.ui.chat.ChatListItem

/**
 * A single chat bubble. User messages are right-aligned with the brand
 * primary color; assistant messages are left-aligned with a neutral surface
 * and include the agent-role badge so the user can tell which agent produced
 * the reply.
 */
@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = alignment,
    ) {
        if (!isUser && message.agentRole != null) {
            AgentRoleBadge(
                role = message.agentRole,
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
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        item.agentRole?.let { role ->
            AgentRoleBadge(
                role = role,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        // Render tool-call cards above the text bubble so the user sees
        // what the agent did before reading its reply.
        if (item.toolCalls.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item.toolCalls.forEach { ToolCallCard(summary = it) }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (item.text.isBlank()) {
                TypingIndicator()
            } else {
                Column {
                    SelectionContainer {
                        Text(
                            text = item.text,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    TypingIndicator(modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
