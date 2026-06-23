package com.hermes.agent.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
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
import com.hermes.agent.ui.home.HomeScreen
import com.hermes.agent.ui.delegate.DelegateScreen
import com.hermes.agent.ui.documents.DocumentsScreen
import com.hermes.agent.ui.experiment.ExperimentScreen
import com.hermes.agent.ui.memory.MemoryScreen
import com.hermes.agent.ui.settings.SettingsScreen
import com.hermes.agent.ui.skills.SkillsScreen

private val bottomNavDestinations = listOf(
    TopLevelDestination.HOME,
    TopLevelDestination.CONVERSATIONS,
    TopLevelDestination.SCHEDULE,
    TopLevelDestination.MEMORY,
)

@Composable
fun HermesNavGraph() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

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
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
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
            modifier = Modifier.padding(innerPadding),
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
                ConversationsScreen(
                    onOpenConversation = { navController.navigate(TopLevelDestination.chatRoute(it)) },
                    onNewConversation  = { navController.navigate(TopLevelDestination.chatRoute(it)) },
                )
            }
            composable(
                route = TopLevelDestination.CHAT.route,
                arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
            ) { entry ->
                ChatScreen(
                    conversationId = entry.arguments?.getString("conversationId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(TopLevelDestination.DOCUMENTS.route) { DocumentsScreen() }
            composable(TopLevelDestination.MEMORY.route)    { MemoryScreen() }
            composable(TopLevelDestination.SKILLS.route)   { SkillsScreen() }
            composable(TopLevelDestination.CONNECT.route)  { ConnectScreen() }
            composable(TopLevelDestination.SCHEDULE.route) { CronScreen() }
            composable(TopLevelDestination.DELEGATE.route) { DelegateScreen() }
            composable(TopLevelDestination.EXPERIMENT.route) { ExperimentScreen() }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(onNavigate = { route -> navController.navigate(route) })
            }
        }
    }
}
