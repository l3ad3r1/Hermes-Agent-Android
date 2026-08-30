package com.hermes.agent.ui.navigation
import com.hermes.agent.domain.settings.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.core.settings.HermesSettings
import com.hermes.agent.ui.theme.alt.ThemeStyle
import com.hermes.agent.ui.theme.alt.tileAccent
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hermes.agent.ui.chat.ChatScreen
import com.hermes.agent.ui.connect.ConnectScreen
import com.hermes.agent.ui.cron.CronScreen
import com.hermes.agent.ui.delegate.DelegateScreen
import com.hermes.agent.ui.documents.DocumentsScreen
import com.hermes.agent.ui.evolution.RefinePromptScreen
import com.hermes.agent.ui.evolution.RefineSkillScreen
import com.hermes.agent.ui.experiment.ExperimentScreen
import com.hermes.agent.ui.home.HomeScreen
import com.hermes.agent.ui.kanban.KanbanBoardScreen
import com.hermes.agent.ui.kanban.TicketDetailScreen
import com.hermes.agent.ui.learning.LearningScreen
import com.hermes.agent.ui.ledger.LedgerScreen
import com.hermes.agent.ui.logs.LogScreen
import com.hermes.agent.ui.memory.MemoryScreen
import com.hermes.agent.ui.plugins.PluginsScreen
import com.hermes.agent.ui.sessions.SessionBrowserScreen
import com.hermes.agent.ui.settings.AboutSettingsScreen
import com.hermes.agent.ui.settings.AdvancedSettingsScreen
import com.hermes.agent.ui.settings.AppearanceSettingsScreen
import com.hermes.agent.ui.settings.AssistantSettingsScreen
import com.hermes.agent.ui.settings.ProvidersSettingsScreen
import com.hermes.agent.ui.settings.ConnectionsSettingsScreen
import com.hermes.agent.ui.settings.ProactiveSettingsScreen
import com.hermes.agent.ui.settings.SettingsScreen
import com.hermes.agent.ui.settings.ModulesSettingsScreen
import com.hermes.agent.ui.skills.SkillsScreen

private val bottomNavDestinations = listOf(
    TopLevelDestination.HOME,
    TopLevelDestination.CONVERSATIONS,
    TopLevelDestination.KANBAN,
    TopLevelDestination.SETTINGS,
)

@Composable
fun HermesNavGraph(
    startAtSettings: Boolean = false,
    /** True once, right after a cold/warm start driven by a share/voice/reply intent. */
    startPendingChatIntent: Boolean = false,
    onPendingChatIntentConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val context = LocalContext.current
    val themeStyleKey by HermesSettings.themeStyleFlow(context)
        .collectAsStateWithLifecycle(initialValue = HermesSettings.THEME_STYLE_CLASSIC)
    val themeStyle = ThemeStyle.fromStorageKey(themeStyleKey)
    val themeAccentArgb by HermesSettings.themeAccentColorFlow(context)
        .collectAsStateWithLifecycle(initialValue = HermesSettings.themeAccentColor(context))
    val accentSeed = themeAccentArgb?.let { Color(it) }

    androidx.compose.runtime.LaunchedEffect(startAtSettings) {
        if (startAtSettings) navController.navigate(TopLevelDestination.SETTINGS.route)
    }

    // Share-to-Hermes / notification-reply / voice quick-tile: jump straight
    // into a fresh chat. ChatScreen itself consumes the queued PendingChatIntent
    // action (send the text, or arm voice listening) once it opens.
    androidx.compose.runtime.LaunchedEffect(startPendingChatIntent) {
        if (startPendingChatIntent) {
            val newId = java.util.UUID.randomUUID().toString()
            navController.navigate(TopLevelDestination.chatRoute(newId))
            onPendingChatIntentConsumed()
        }
    }

    val showBottomBar = currentRoute in bottomNavDestinations.map { it.route }.toSet()

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    val scheme = MaterialTheme.colorScheme
                    bottomNavDestinations.forEachIndexed { index, dest ->
                        val selected = currentRoute == dest.route
                        // Cortex/Material You: each tab gets its own accent rather
                        // than one repeated hue, matching the "colour per section"
                        // feel of the tile grid rather than a flat monochrome bar.
                        val accent = if (themeStyle != ThemeStyle.CLASSIC) {
                            tileAccent(themeStyle, scheme, index, accentSeed)
                        } else {
                            scheme.onSecondaryContainer
                        }
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            // Material3's built-in selection indicator is a hardcoded pill
                            // (NavigationBarTokens.ActiveIndicatorShape = CircleShape, not
                            // themeable in 1.3.0). Hide it via indicatorColor and draw our own
                            // square-with-rounded-corners indicator around the icon instead.
                            icon = {
                                Box(
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(
                                            if (selected) {
                                                if (themeStyle != ThemeStyle.CLASSIC) {
                                                    accent.copy(alpha = 0.18f)
                                                } else {
                                                    scheme.secondaryContainer
                                                }
                                            } else {
                                                Color.Transparent
                                            },
                                        )
                                        .padding(horizontal = 20.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        dest.icon,
                                        contentDescription = null,
                                        tint = if (selected && themeStyle != ThemeStyle.CLASSIC) accent else LocalContentColor.current,
                                    )
                                }
                            },
                            label = { Text(dest.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = Color.Transparent,
                                selectedTextColor = if (themeStyle != ThemeStyle.CLASSIC) accent else scheme.onSurface,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding: PaddingValues ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.HOME.route,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            composable(TopLevelDestination.HOME.route) {
                HomeScreen(
                    onOpenConversations = { navController.navigate(TopLevelDestination.CONVERSATIONS.route) },
                    onNewChat = { navController.navigate(TopLevelDestination.chatRoute(it)) },
                    onOpenConnections = { navController.navigate(TopLevelDestination.CONNECT.route) },
                    onOpenKanban = { navController.navigate(TopLevelDestination.KANBAN.route) },
                    onOpenMemory = { navController.navigate(TopLevelDestination.MEMORY.route) },
                    onOpenSkills = { navController.navigate(TopLevelDestination.SKILLS.route) },
                    onOpenSchedule = { navController.navigate(TopLevelDestination.SCHEDULE.route) },
                    onOpenExperiment = { navController.navigate(TopLevelDestination.EXPERIMENT.route) },
                    onOpenDocuments = { navController.navigate(TopLevelDestination.DOCUMENTS.route) },
                )
            }
            composable(TopLevelDestination.CONVERSATIONS.route) {
                SessionBrowserScreen(
                    onOpenSession = { navController.navigate(TopLevelDestination.chatRoute(it)) },
                    onNewSession = {
                        val newId = java.util.UUID.randomUUID().toString()
                        navController.navigate(TopLevelDestination.chatRoute(newId))
                    },
                )
            }
            composable(
                route = TopLevelDestination.CHAT.route,
                arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
            ) { entry ->
                ChatScreen(
                    conversationId = entry.arguments?.getString("conversationId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onNewChat = {
                        val newId = java.util.UUID.randomUUID().toString()
                        navController.navigate(TopLevelDestination.chatRoute(newId))
                    },
                    onOpenConversation = { id ->
                        navController.navigate(TopLevelDestination.chatRoute(id))
                    },
                )
            }
            composable(TopLevelDestination.DOCUMENTS.route) { DocumentsScreen(onBack = { navController.popBackStack() }) }
            composable(TopLevelDestination.MEMORY.route)    { MemoryScreen(onBack = { navController.popBackStack() }) }
            composable(TopLevelDestination.KANBAN.route) {
                KanbanBoardScreen(
                    onTicketClick = { navController.navigate(TopLevelDestination.ticketRoute(it)) },
                )
            }
            composable(
                route = TopLevelDestination.TICKET.route,
                arguments = listOf(navArgument("ticketId") { type = NavType.StringType }),
            ) { entry ->
                TicketDetailScreen(
                    ticketId = entry.arguments?.getString("ticketId").orEmpty(),
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(TopLevelDestination.SKILLS.route)   { SkillsScreen(onBack = { navController.popBackStack() }) }
            composable(TopLevelDestination.CONNECT.route)  { ConnectScreen(onBack = { navController.popBackStack() }) }
            composable(TopLevelDestination.SCHEDULE.route) { CronScreen(onBack = { navController.popBackStack() }) }
            composable(TopLevelDestination.DELEGATE.route) { DelegateScreen() }
            composable(TopLevelDestination.EXPERIMENT.route) { ExperimentScreen(onBack = { navController.popBackStack() }) }
            
            // Settings parent and children
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(onNavigate = { route -> navController.navigate(route) })
            }
            composable("settings_assistant") {
                AssistantSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenProviders = { navController.navigate("settings_providers") },
                )
            }
            composable("settings_providers") { ProvidersSettingsScreen(onBack = { navController.popBackStack() }) }
            composable("settings_appearance") { AppearanceSettingsScreen(onBack = { navController.popBackStack() }) }
            composable("settings_connections") { ConnectionsSettingsScreen(onBack = { navController.popBackStack() }) }
            composable("settings_advanced") { AdvancedSettingsScreen(onBack = { navController.popBackStack() }) }
            composable("settings_modules") { ModulesSettingsScreen(onBack = { navController.popBackStack() }) }
            composable("settings_proactive") { ProactiveSettingsScreen(onBack = { navController.popBackStack() }) }
            composable("settings_about") { AboutSettingsScreen(onBack = { navController.popBackStack() }) }
            
            composable("plugins") { PluginsScreen(onBack = { navController.popBackStack() }) }
            composable("logs") { LogScreen(onBack = { navController.popBackStack() }) }
            composable("activity_ledger") { LedgerScreen(onBack = { navController.popBackStack() }) }
            composable("learning") { LearningScreen(onBack = { navController.popBackStack() }) }
            composable("refine_skills") { RefineSkillScreen(onBack = { navController.popBackStack() }) }
            composable("refine_prompts") { RefinePromptScreen(onBack = { navController.popBackStack() }) }
        }
    }
}
