package com.hermes.agent.ui.settings
import com.hermes.agent.domain.settings.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
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
    val context = androidx.compose.ui.platform.LocalContext.current
    fun nav(route: String): () -> Unit = { onNavigate(route) }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = { SlimTopBar(title = stringResource(R.string.nav_settings)) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsGroup(
                "Assistant & appearance",
                listOf(
                    NavItem(Icons.Outlined.AccountCircle, "Assistant", "Providers, local model, reasoning effort, voice", nav("settings_assistant")),
                    NavItem(Icons.Outlined.Tune, "Providers", "Cloud API keys, models, and automatic fallback", nav("settings_providers")),
                    NavItem(Icons.Outlined.ColorLens, "Appearance", "Theme, dark mode, and accent colour", nav("settings_appearance")),
                ),
            )

            SettingsGroup(
                "Connections & automation",
                listOf(
                    NavItem(Icons.Outlined.SettingsEthernet, "Connections", "Home Assistant, remote shell, MCP servers, API server", nav("settings_connections")),
                    NavItem(Icons.Outlined.Link, "Messaging", "Telegram, Discord, Signal, WhatsApp gateways", nav("connect")),
                    NavItem(Icons.Outlined.Schedule, "CRON routines", "Recurring agent tasks on a schedule", nav("schedule")),
                    NavItem(Icons.AutoMirrored.Outlined.Send, "Delegate", "Background agent tasks and their results", nav("delegate")),
                    NavItem(Icons.Outlined.Notifications, "Proactive", "Digest, nudges, quiet hours, ping budget", nav("settings_proactive")),
                    NavItem(Icons.Outlined.Extension, "Modules", "Download verified modules from the shared repository", nav("settings_modules")),
                ),
            )

            SettingsGroup(
                "Knowledge & skills",
                listOf(
                    NavItem(Icons.Outlined.Psychology, "Memory", "View and manage agent memories", nav("memory")),
                    NavItem(Icons.Outlined.AutoAwesome, "Learning", "Facts learned, your profile, auto-created skills", nav("learning")),
                    NavItem(Icons.AutoMirrored.Outlined.LibraryBooks, "Knowledge base", "Documents and files indexed for retrieval", nav("documents")),
                    NavItem(Icons.Outlined.Stars, "Skills & tools", "Browse and manage the agent's skills and tools", nav("skills")),
                    NavItem(Icons.Outlined.Science, "Refine skills", "Improve a skill from how it was actually used", nav("refine_skills")),
                    NavItem(Icons.Outlined.Description, "Agent operating notes", "Learned guidance layered on each agent's prompt", nav("refine_prompts")),
                ),
            )

            SettingsGroup(
                "Activity & diagnostics",
                listOf(
                    NavItem(Icons.Outlined.Insights, "Usage & cost", "Tokens, estimated spend, tool-call counts", nav("usage_insights")),
                    NavItem(Icons.Outlined.History, "What Hermes did", "Activity ledger: tool runs and delegated tasks", nav("activity_ledger")),
                    NavItem(Icons.Outlined.Science, "A/B experiment", "Compare two models on the same prompt", nav("experiment")),
                    NavItem(Icons.Outlined.Article, "Logs", "View, copy, or share app logs for troubleshooting", nav("logs")),
                ),
            )

            SettingsGroup(
                "Device & security",
                listOf(
                    NavItem(Icons.Outlined.Accessibility, "App control", "Accessibility Service — lets the agent drive other apps") {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    NavItem(Icons.Outlined.Build, "Advanced", "Backup, updates, files access, self-evolution", nav("settings_advanced")),
                    NavItem(Icons.Outlined.Info, "About, permissions & security", "Version, app permissions, companion apps, security audit", nav("settings_about")),
                ),
            )
        }
    }
}
