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
import com.hermes.agent.ui.conversations.ConversationsScreen
import com.hermes.agent.ui.documents.DocumentsScreen
import com.hermes.agent.ui.memory.MemoryScreen
import com.hermes.agent.ui.plugins.PluginsScreen
import com.hermes.agent.ui.settings.SettingsScreen

/**
 * Top-level Hermes nav graph.
 *
 * Phase 2 destinations (in bottom-nav order):
 *   - Conversations (start)
 *   - Memory
 *   - Documents
 *   - Settings
 *
 * Chat is a full-screen route with its own top app bar; it's not in the
 * bottom nav.
 */
@Composable
fun HermesNavGraph() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = currentRoute in setOf(
        TopLevelDestination.CONVERSATIONS.route,
        TopLevelDestination.MEMORY.route,
        TopLevelDestination.DOCUMENTS.route,
        TopLevelDestination.PLUGINS.route,
        TopLevelDestination.SETTINGS.route,
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    listOf(
                        TopLevelDestination.CONVERSATIONS,
                        TopLevelDestination.MEMORY,
                        TopLevelDestination.DOCUMENTS,
                        TopLevelDestination.PLUGINS,
                        TopLevelDestination.SETTINGS,
                    ).forEach { dest ->
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
            startDestination = TopLevelDestination.CONVERSATIONS.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopLevelDestination.CONVERSATIONS.route) {
                ConversationsScreen(
                    onOpenConversation = { id ->
                        navController.navigate(TopLevelDestination.chatRoute(id))
                    },
                    onNewConversation = { id ->
                        navController.navigate(TopLevelDestination.chatRoute(id))
                    },
                )
            }
            composable(
                route = TopLevelDestination.CHAT.route,
                arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val conversationId = backStackEntry.arguments?.getString("conversationId").orEmpty()
                ChatScreen(
                    conversationId = conversationId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(TopLevelDestination.MEMORY.route) {
                MemoryScreen()
            }
            composable(TopLevelDestination.DOCUMENTS.route) {
                DocumentsScreen()
            }
            composable(TopLevelDestination.PLUGINS.route) {
                PluginsScreen()
            }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen()
            }
        }
    }
}
