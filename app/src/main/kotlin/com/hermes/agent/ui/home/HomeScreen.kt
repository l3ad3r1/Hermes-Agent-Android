package com.hermes.agent.ui.home
import com.hermes.agent.domain.settings.*

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ViewKanban
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.core.settings.HermesSettings
import com.hermes.agent.domain.model.Conversation
import com.hermes.agent.ui.bloub.HermesBot
import com.hermes.agent.core.theme.GeistMono
import com.hermes.agent.ui.theme.alt.OutlinedSpaceTile
import com.hermes.agent.ui.theme.alt.SpaceTile
import com.hermes.agent.ui.theme.alt.ThemeStyle
import com.hermes.agent.ui.theme.alt.contrastInkAcross
import com.hermes.agent.ui.theme.alt.tileAccent

/**
 * Home dashboard — the app's landing surface: greeting, the active cloud model,
 * quick actions (new chat, messaging), and the real recent-conversation list.
 */
@Composable
fun HomeScreen(
    onOpenConversations: () -> Unit,
    onNewChat: (conversationId: String) -> Unit,
    onOpenConnections: () -> Unit,
    onOpenKanban: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onOpenSchedule: () -> Unit = {},
    onOpenExperiment: () -> Unit = {},
    onOpenDocuments: () -> Unit = {},
    onOpenHaDashboard: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val threads by viewModel.recentThreads.collectAsStateWithLifecycle()
    val model by viewModel.modelName.collectAsStateWithLifecycle()
    val showHa by viewModel.showHaDashboard.collectAsStateWithLifecycle()
    val presence by viewModel.presence.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val themeStyleKey by HermesSettings.themeStyleFlow(context)
        .collectAsStateWithLifecycle(initialValue = HermesSettings.THEME_STYLE_CLASSIC)
    val themeStyle = ThemeStyle.fromStorageKey(themeStyleKey)
    val themeAccentArgb by HermesSettings.themeAccentColorFlow(context)
        .collectAsStateWithLifecycle(initialValue = HermesSettings.themeAccentColor(context))
    val accentSeed = themeAccentArgb?.let { Color(it) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // Presence header: pokeable face + status line. Settings lives in the
        // bottom navigation, so the header keeps the full width for the status.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 86.dp is the whole viewBox, not the ball: the bot itself is about
            // 0.63 of it, and the margin is what the orbit rings need to stay in
            // frame. Colour and shape come from the customiser.
            HermesBot(
                mood = presence.mood,
                size = 86.dp,
                // Poke Hermes: the body collapses and the particles spiral in.
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = viewModel::poke,
                ),
            )
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text(presence.greeting, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                Text(
                    presence.statusLine,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onBackground,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Active-model card. Cortex sweeps two of its accents for a splash of
        // colour; Material You fills flat with one wallpaper accent, matching
        // its tiles; Classic keeps the monochrome surface blend.
        val modelCardStops = when (themeStyle) {
            ThemeStyle.MATERIAL_YOU -> {
                val fill = tileAccent(themeStyle, scheme, 1, accentSeed)
                listOf(fill, fill)
            }
            ThemeStyle.CORTEX -> listOf(
                tileAccent(themeStyle, scheme, 1, accentSeed),
                tileAccent(themeStyle, scheme, 4, accentSeed),
            )
            ThemeStyle.CLASSIC -> listOf(scheme.surfaceVariant, scheme.surfaceContainerHigh)
        }
        val modelCardGradient = Brush.linearGradient(modelCardStops)
        // The label sits on whichever gradient won, and those grounds span
        // Cortex's deep ember, a wallpaper-derived Material You sweep and
        // Classic's near-white light surface. A hardcoded white reads on the
        // first and disappears on the last, so the ink is chosen from the stops
        // the card actually drew — on the worst of them, since the text has to
        // survive both ends of the gradient rather than its average.
        val modelCardInk = contrastInkAcross(modelCardStops)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(modelCardGradient)
                .padding(18.dp),
        ) {
            Text("Active model", color = modelCardInk.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                model.ifBlank { "not configured" },
                fontFamily = GeistMono,
                color = modelCardInk,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(16.dp))

        // Agent Superpower Hub Grid
        SectionLabel("Agent Superpowers")
        Spacer(Modifier.height(10.dp))

        // Two columns is right on a phone and wrong on a tablet: at 1100dp the
        // tiles were ~530dp square and one row filled most of a landscape screen.
        // Pick the column count from the width actually available so the eight
        // tiles land as 4x2 on a tablet and stay 2x4 on a phone.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = when {
                maxWidth < 500.dp -> 2
                maxWidth < 840.dp -> 3
                else -> 4
            }
            val tiles = buildList {
                add(SuperpowerTile("New Chat", "Ask or delegate", Icons.AutoMirrored.Filled.Chat, 0) {
                    viewModel.createNewConversation(onNewChat)
                })
                add(SuperpowerTile("Kanban Board", "Task queue", Icons.Filled.ViewKanban, 1, onOpenKanban))
                add(SuperpowerTile("Starmap Memory", "Knowledge graph", Icons.Filled.Hub, 2, onOpenMemory))
                add(SuperpowerTile("Skill Studio", "Custom tools", Icons.Filled.Psychology, 3, onOpenSkills))
                add(SuperpowerTile("CRON Routines", "Scheduled triggers", Icons.Filled.Schedule, 4, onOpenSchedule))
                add(SuperpowerTile("Messaging & Bot", "Telegram gateway", Icons.Filled.Forum, 0, onOpenConnections))
                add(SuperpowerTile("A/B Benchmark", "Latency & tok/s", Icons.Filled.Bolt, 1, onOpenExperiment))
                add(SuperpowerTile("Knowledge Base", "Documents & RAG", Icons.AutoMirrored.Filled.LibraryBooks, 2, onOpenDocuments))
                if (showHa) add(SuperpowerTile("Home Assistant", "Smart-home dashboard", Icons.Filled.Dashboard, 3, onOpenHaDashboard))
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tiles.chunked(columns).forEach { rowTiles ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        rowTiles.forEach { tile ->
                            QuickAction(
                                title = tile.title,
                                subtitle = tile.subtitle,
                                icon = tile.icon,
                                accent = tileAccent(themeStyle, scheme, tile.accentIndex, accentSeed),
                                themeStyle = themeStyle,
                                modifier = Modifier.weight(1f),
                                onClick = tile.onClick,
                            )
                        }
                        // A short last row keeps its tiles the same width as the
                        // rows above rather than stretching to fill.
                        repeat(columns - rowTiles.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Recent threads
        SectionHeader("Recent threads", action = "Open", onAction = onOpenConversations)
        Spacer(Modifier.height(11.dp))
        if (threads.isEmpty()) {
            EmptyHint("No conversations yet — start a new chat.")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                threads.forEachIndexed { index, thread ->
                    ThreadRow(thread, themeStyle, index, accentSeed, onClick = { onNewChat(thread.id) })
                }
            }
        }
    }
}


/**
 * One superpower tile. Every element sits at a fixed offset from the top of the
 * card so tiles read as one repeated template: a title that always reserves two
 * lines whatever the device font scale, then a single-line subtitle. Because the title always reserves two lines and the subtitle one,
 * every tile resolves to the same height without an intrinsic-measure pass.
 */
@Composable
private fun QuickAction(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    themeStyle: ThemeStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // Both variants are the same tile: same squircle, same 1:1 footprint, same
    // title/subtitle above a large icon. Classic drops the fill and the accent
    // for a hairline outline and monochrome contents; the coloured styles keep
    // the accent glow and the tinted icon.
    // A tile was square at any width. Two columns land near 180dp on a phone, so
    // that read well - but the same weight(1f) resolves to about 530dp on a
    // 1100dp-wide tablet, and a 530dp-tall tile ate three quarters of a landscape
    // screen. Stay square while that is a sensible size and stop growing after
    // that: the tile still fills its column, it just no longer gets taller with it.
    BoxWithConstraints(modifier) {
        val side = minOf(maxWidth, MAX_TILE_SIDE)
        val tileModifier = Modifier.fillMaxWidth().height(side)
        if (themeStyle == ThemeStyle.CLASSIC) {
            OutlinedSpaceTile(
                title = title,
                subtitle = subtitle,
                icon = icon,
                modifier = tileModifier,
                onClick = onClick,
            )
        } else {
            SpaceTile(
                title = title,
                subtitle = subtitle,
                icon = icon,
                accent = accent,
                modifier = tileModifier,
                // Material You fills the card with the wallpaper accent outright;
                // Cortex keeps the softer glow its fixed palette was tuned for.
                solid = themeStyle == ThemeStyle.MATERIAL_YOU,
                onClick = onClick,
            )
        }
    }
}

/** One entry in the superpower grid, so the grid can be laid out by column count. */
private data class SuperpowerTile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accentIndex: Int,
    val onClick: () -> Unit,
)

/** Widest a superpower tile gets before it stops growing with its column. */
private val MAX_TILE_SIDE = 150.dp
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
private fun ThreadRow(
    thread: Conversation,
    themeStyle: ThemeStyle,
    index: Int,
    accentSeed: Color?,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // A different accent per row under Cortex/Material You — a splash of
    // colour down the thread list rather than one repeated primary dot.
    val dotColor = if (themeStyle != ThemeStyle.CLASSIC) {
        tileAccent(themeStyle, scheme, index, accentSeed)
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
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(dotColor),
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
