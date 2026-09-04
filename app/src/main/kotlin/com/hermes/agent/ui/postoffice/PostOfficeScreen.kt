package com.hermes.agent.ui.postoffice

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Markunread
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.agent.core.settings.HermesSettings
import com.hermes.agent.domain.model.Conversation
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.ui.components.SlimTopBar
import com.hermes.agent.ui.theme.alt.ThemeStyle
import com.hermes.agent.ui.theme.alt.tileAccent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class PostOfficeViewModel @Inject constructor(
    conversationRepository: ConversationRepository,
) : ViewModel() {

    /**
     * Mailbox conversations written by courier or HermesApiServer, identified
     * by the "po-" id prefix (or "PO:" title prefix).
     */
    val conversations: StateFlow<List<Conversation>> =
        conversationRepository.observeConversations()
            .map { list ->
                list.filter { it.id.startsWith("po-") || it.title.startsWith("PO:") }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )
}

@Composable
fun PostOfficeScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    viewModel: PostOfficeViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val themeStyleKey by HermesSettings.themeStyleFlow(context)
        .collectAsStateWithLifecycle(initialValue = HermesSettings.THEME_STYLE_CLASSIC)
    val themeStyle = ThemeStyle.fromStorageKey(themeStyleKey)
    val themeAccentArgb by HermesSettings.themeAccentColorFlow(context)
        .collectAsStateWithLifecycle(initialValue = HermesSettings.themeAccentColor(context))
    val accentSeed = themeAccentArgb?.let { Color(it) }

    Scaffold(
        topBar = {
            SlimTopBar(
                title = "Post Office",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(scheme.background)
                .padding(innerPadding),
        ) {
            if (conversations.isEmpty()) {
                EmptyPostOfficeState(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            text = "Cross-agent threads with Claude, Codex, Antigravity, and other agents.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    items(conversations, key = { it.id }) { thread ->
                        PostOfficeThreadRow(
                            thread = thread,
                            themeStyle = themeStyle,
                            accentSeed = accentSeed,
                            onClick = { onOpenConversation(thread.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PostOfficeThreadRow(
    thread: Conversation,
    themeStyle: ThemeStyle,
    accentSeed: Color?,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dotColor = if (themeStyle != ThemeStyle.CLASSIC) {
        tileAccent(themeStyle, scheme, 0, accentSeed)
    } else {
        scheme.primary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(scheme.surface)
            .border(1.dp, scheme.outline.copy(alpha = 0.25f), MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(dotColor),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = thread.title.ifBlank { thread.id },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (thread.messageCount > 0) {
                    Text(
                        text = "${thread.messageCount} msg${if (thread.messageCount > 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (thread.lastMessagePreview.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = thread.lastMessagePreview,
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (thread.updatedAt > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(thread.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun EmptyPostOfficeState(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Markunread,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = scheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Post Office messages yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Cross-agent mail between Claude, Codex, Antigravity, and Hermes will appear here once received.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
