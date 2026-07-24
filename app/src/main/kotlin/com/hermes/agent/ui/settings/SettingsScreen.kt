package com.hermes.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermes.agent.R
import com.hermes.agent.ui.components.SlimTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigate: (String) -> Unit = {},
) {
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            SlimTopBar(title = stringResource(R.string.nav_settings))
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(text = "Configuration")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    NavRow(
                        icon = Icons.Outlined.AccountCircle,
                        title = "Assistant",
                        subtitle = "Cloud LLM provider, models, and chat behaviour",
                        onClick = { onNavigate("settings_assistant") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.ColorLens,
                        title = "Appearance",
                        subtitle = "App theme and dark mode",
                        onClick = { onNavigate("settings_appearance") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.Alarm,
                        title = "Daybook",
                        subtitle = "Wake-ups, weather & calendar",
                        onClick = { onNavigate("settings_alarms") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.SettingsEthernet,
                        title = "Connections",
                        subtitle = "Local API server and remote shell",
                        onClick = { onNavigate("settings_connections") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.Build,
                        title = "Advanced",
                        subtitle = "Backup, updates, and session export",
                        onClick = { onNavigate("settings_advanced") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.Notifications,
                        title = "Proactive",
                        subtitle = "Digest, nudges, quiet hours, and the ping budget",
                        onClick = { onNavigate("settings_proactive") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.History,
                        title = "What Jeeves did",
                        subtitle = "Activity ledger: tool runs and delegated tasks",
                        onClick = { onNavigate("activity_ledger") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.Info,
                        title = "About & Security",
                        subtitle = "App info, Knox, and hardware-backed Keystore",
                        onClick = { onNavigate("settings_about") },
                    )
                }
            }

            SectionHeader(text = "Features")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    NavRow(
                        icon = Icons.Outlined.Psychology,
                        title = "Memory",
                        subtitle = "View and manage agent memories",
                        onClick = { onNavigate("memory") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.AutoAwesome,
                        title = "Learning",
                        subtitle = "Facts learned, your profile, and auto-created skills",
                        onClick = { onNavigate("learning") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.Description,
                        title = "Artifacts",
                        subtitle = "Documents and files indexed for retrieval",
                        onClick = { onNavigate("documents") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.Stars,
                        title = "Skills & Tools",
                        subtitle = "Browse and manage the agent's skills and tools",
                        onClick = { onNavigate("skills") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.Science,
                        title = "Refine skills",
                        subtitle = "Improve a skill from how it was actually used",
                        onClick = { onNavigate("refine_skills") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.Link,
                        title = "Messaging",
                        subtitle = "Configure Telegram, Discord, Signal, WhatsApp",
                        onClick = { onNavigate("connect") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.Schedule,
                        title = "CRON",
                        subtitle = "Manage cron jobs and recurring agent tasks",
                        onClick = { onNavigate("schedule") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.AutoMirrored.Outlined.Send,
                        title = "Delegate",
                        subtitle = "Run background agent tasks and see their results",
                        onClick = { onNavigate("delegate") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.Science,
                        title = "Experiment",
                        subtitle = "Compare two models side-by-side on the same prompt",
                        onClick = { onNavigate("experiment") },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Outlined.Article,
                        title = "Logs",
                        subtitle = "View, copy, or share app logs for troubleshooting",
                        onClick = { onNavigate("logs") },
                    )
                }
            }
        }
    }
}
