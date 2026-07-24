package com.hermes.agent.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hermes.agent.ui.chat.ChatScreen
import com.hermes.agent.ui.connect.ConnectScreen
import com.hermes.agent.ui.conversations.ConversationsScreen
import com.hermes.agent.ui.cron.CronScreen
import com.hermes.agent.ui.delegate.DelegateScreen
import com.hermes.agent.ui.documents.DocumentsScreen
import com.hermes.agent.ui.evolution.RefineSkillScreen
import com.hermes.agent.ui.experiment.ExperimentScreen
import com.hermes.agent.ui.home.HomeScreen
import com.hermes.agent.ui.kanban.KanbanBoardScreen
import com.hermes.agent.ui.kanban.TicketDetailScreen
import com.hermes.agent.ui.learning.LearningScreen
import com.hermes.agent.ui.ledger.LedgerScreen
import com.hermes.agent.ui.logs.LogScreen
import com.hermes.agent.ui.memory.MemoryScreen
import com.hermes.agent.ui.sessions.SessionBrowserScreen
import com.hermes.agent.ui.settings.AboutSettingsScreen
import com.hermes.agent.ui.settings.AdvancedSettingsScreen
import com.hermes.agent.ui.settings.AlarmSettingsScreen
import com.hermes.agent.ui.settings.AppearanceSettingsScreen
import com.hermes.agent.ui.settings.AssistantSettingsScreen
import com.hermes.agent.ui.settings.ConnectionsSettingsScreen
import com.hermes.agent.ui.settings.ProactiveSettingsScreen
import com.hermes.agent.ui.settings.SettingsScreen
import com.hermes.agent.ui.skills.SkillsScreen

private val bottomNavDestinations = listOf(
    TopLevelDestination.HOME,
    TopLevelDestination.CONVERSATIONS,
    TopLevelDestination.KANBAN,
    TopLevelDestination.SETTINGS,
)

@Composable
fun HermesNavGraph(startAtSettings: Boolean = false) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    androidx.compose.runtime.LaunchedEffect(startAtSettings) {
        if (startAtSettings) navController.navigate(TopLevelDestination.SETTINGS.route)
    }

    val showBottomBar = currentRoute in bottomNavDestinations.map { it.route }.toSet()

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavDestinations.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = null) },
                            label = { Text(dest.label) },
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
                    onOpenSettings = { navController.navigate(TopLevelDestination.SETTINGS.route) },
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
            composable(TopLevelDestination.EXPERIMENT.route) { ExperimentScreen() }
            
            // Settings parent and children
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(onNavigate = { route -> navController.navigate(route) })
            }
            composable("settings_assistant") { AssistantSettingsScreen(onBack = { navController.popBackStack() }) }
            composable("settings_appearance") { AppearanceSettingsScreen(onBack = { navController.popBackStack() }) }
            composable("settings_alarms") { AlarmSettingsScreen(onBack = { navController.popBackStack() }) }
            composable("settings_connections") { ConnectionsSettingsScreen(onBack = { navController.popBackStack() }) }
            composable("settings_advanced") { AdvancedSettingsScreen(onBack = { navController.popBackStack() }) }
            composable("settings_proactive") { ProactiveSettingsScreen(onBack = { navController.popBackStack() }) }
            composable("settings_about") { AboutSettingsScreen(onBack = { navController.popBackStack() }) }
            
            composable("logs") { LogScreen(onBack = { navController.popBackStack() }) }
            composable("activity_ledger") { LedgerScreen(onBack = { navController.popBackStack() }) }
            composable("learning") { LearningScreen(onBack = { navController.popBackStack() }) }
            composable("refine_skills") { RefineSkillScreen(onBack = { navController.popBackStack() }) }
        }
    }
}
