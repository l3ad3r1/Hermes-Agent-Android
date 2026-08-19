package com.hermes.agent.ui.chat.components
import com.hermes.agent.domain.settings.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.agent.core.theme.GeistMono

data class SlashCommand(
    val command: String,
    val syntax: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val template: String,
)

val HERMES_SLASH_COMMANDS = listOf(
    SlashCommand(
        command = "/plan",
        syntax = "/plan <task>",
        title = "Plan Project (Ultra-Skill)",
        description = "Generate structured multi-step plan with PREPARED evidence badge",
        icon = Icons.Outlined.AutoAwesome,
        template = "ulw-plan ",
    ),
    SlashCommand(
        command = "/research",
        syntax = "/research <query>",
        title = "Deep Research (Ultra-Skill)",
        description = "Execute multi-step web and document investigation",
        icon = Icons.Outlined.Search,
        template = "ulw-research ",
    ),
    SlashCommand(
        command = "/kanban",
        syntax = "/kanban <task>",
        title = "Kanban Task Decomposition",
        description = "Break complex goals into tickets on your Kanban board",
        icon = Icons.Outlined.ViewColumn,
        template = "Break down into Kanban tickets: ",
    ),
    SlashCommand(
        command = "/model ultrabrain",
        syntax = "/model ultrabrain",
        title = "Maestro Ultrabrain",
        description = "Route task to maximum capability specialist cloud LLM",
        icon = Icons.Outlined.Psychology,
        template = "[ultrabrain] ",
    ),
    SlashCommand(
        command = "/model quick",
        syntax = "/model quick",
        title = "Maestro Quick / Local",
        description = "Short-circuit to ultra-fast low latency inference",
        icon = Icons.Outlined.Bolt,
        template = "[quick] ",
    ),
    SlashCommand(
        command = "/delegate",
        syntax = "/delegate <task>",
        title = "Delegate Subagent",
        description = "Launch isolated subagent background task",
        icon = Icons.Outlined.Send,
        template = "Delegate task: ",
    ),
    SlashCommand(
        command = "/memory",
        syntax = "/memory <query>",
        title = "Search Memory",
        description = "Recall past facts, habits, and user context",
        icon = Icons.Outlined.Psychology,
        template = "Search memory for: ",
    ),
    SlashCommand(
        command = "/export",
        syntax = "/export",
        title = "Export Trajectory",
        description = "Export conversation trajectory to Markdown / JSON",
        icon = Icons.Outlined.Description,
        template = "Export this conversation trajectory",
    ),
    SlashCommand(
        command = "/clear",
        syntax = "/clear",
        title = "Clear Context",
        description = "Reset active chat conversation transcript",
        icon = Icons.Outlined.CleaningServices,
        template = "/clear",
    ),
)

/**
 * Autocomplete popup palette shown when the user types '/' or 'ulw-' in the chat composer.
 */
@Composable
fun SlashCommandPalette(
    currentQuery: String,
    onSelectCommand: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isVisible = currentQuery.startsWith("/") || currentQuery.startsWith("ulw-")
    val queryToken = when {
        currentQuery.startsWith("/") -> currentQuery.removePrefix("/")
        currentQuery.startsWith("ulw-") -> currentQuery.removePrefix("ulw-")
        else -> ""
    }.trim().lowercase()

    val filteredCommands = if (queryToken.isEmpty()) {
        HERMES_SLASH_COMMANDS
    } else {
        HERMES_SLASH_COMMANDS.filter {
            it.command.lowercase().contains(queryToken) ||
                it.title.lowercase().contains(queryToken) ||
                it.description.lowercase().contains(queryToken)
        }
    }

    AnimatedVisibility(
        visible = isVisible && filteredCommands.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "HERMES COMMANDS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp,
                    )
                    Text(
                        text = "${filteredCommands.size} available",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                ) {
                    items(filteredCommands, key = { it.command }) { cmd ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectCommand(cmd) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = cmd.icon,
                                    contentDescription = cmd.title,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(8.dp),
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = cmd.command,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = GeistMono ?: FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "• ${cmd.title}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = cmd.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
