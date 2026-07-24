package com.hermes.agent.ui.home

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.domain.model.Conversation
import com.hermes.agent.ui.components.ExpressiveEyes
import com.hermes.agent.ui.components.HermesDiamond
import com.hermes.agent.core.theme.GeistMono
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Home dashboard — the app's landing surface: greeting, the active cloud model,
 * quick actions (new chat, messaging), and the real recent-conversation list.
 */
@Composable
fun HomeScreen(
    onOpenConversations: () -> Unit,
    onNewChat: (conversationId: String) -> Unit,
    onOpenConnections: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val threads by viewModel.recentThreads.collectAsStateWithLifecycle()
    val model by viewModel.modelName.collectAsStateWithLifecycle()
    val presence by viewModel.presence.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .background(scheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(top = 8.dp, bottom = 26.dp),
    ) {
        // Header: Hermes's face (expressive eyes) + context-aware greeting.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExpressiveEyes(
                mood = presence.mood,
                eyeColor = scheme.primary,
                width = 72.dp,
                height = 40.dp,
                // Poke Hermes: startled eyes + a quip for a few seconds.
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = viewModel::poke,
                ),
            )
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(presence.greeting, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                Text(
                    presence.statusLine,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onBackground,
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(scheme.surface)
                    .border(1.dp, scheme.outline.copy(alpha = 0.4f), RoundedCornerShape(percent = 50))
                    .clickable(onClick = onOpenSettings)
                    .semantics { contentDescription = "Open settings" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = scheme.onBackground,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // Active-model card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(Brush.linearGradient(listOf(scheme.surfaceVariant, scheme.surfaceContainerHigh)))
                .padding(18.dp),
        ) {
            Text("Active model", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                model.ifBlank { "not configured" },
                fontFamily = GeistMono,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(16.dp))

        // Quick actions
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            QuickAction(
                title = "New chat",
                subtitle = "Ask or delegate",
                modifier = Modifier.weight(1f),
                onClick = { viewModel.createNewConversation(onNewChat) },
            )
            QuickAction(
                title = "Messaging",
                subtitle = "Link a platform",
                modifier = Modifier.weight(1f),
                onClick = onOpenConnections,
            )
        }

        Spacer(Modifier.height(20.dp))

        // Recent threads
        SectionHeader("Recent threads", action = "Open", onAction = onOpenConversations)
        Spacer(Modifier.height(11.dp))
        if (threads.isEmpty()) {
            EmptyHint("No conversations yet — start a new chat.")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                threads.forEach { thread -> ThreadRow(thread, onClick = { onNewChat(thread.id) }) }
            }
        }
    }
}


@Composable
private fun QuickAction(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(scheme.surface)
            .border(1.dp, scheme.outline.copy(alpha = 0.25f), MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(scheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            HermesDiamond(tileSize = 16.dp, glyphSize = 7.dp, cornerRadius = 4.dp, glow = false)
        }
        Spacer(Modifier.height(10.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = scheme.onSurface)
        Text(subtitle, fontSize = 11.5.sp, color = scheme.onSurfaceVariant)
    }
}

/** Section heading without a trailing action link (cf. [SectionHeader]). */
@Composable
private fun SectionLabel(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun SectionHeader(title: String, action: String, onAction: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            color = scheme.outline,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onAction) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
            )
        }
    }
}

@Composable
private fun ThreadRow(thread: Conversation, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(scheme.surface)
            .border(1.dp, scheme.outline.copy(alpha = 0.25f), MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(scheme.primary),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                thread.title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (thread.lastMessagePreview.isNotBlank()) {
                Text(
                    thread.lastMessagePreview,
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(scheme.surface)
            .border(1.dp, scheme.outline.copy(alpha = 0.25f), MaterialTheme.shapes.medium)
            .padding(16.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
}
