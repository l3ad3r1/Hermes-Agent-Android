package com.hermes.agent.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Top-level destinations. Used by both the bottom-nav bar and the
 * NavController type-safe routes.
 */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    CONVERSATIONS(
        route = "conversations",
        label = "Conversations",
        icon = Icons.Outlined.Forum,
    ),
    CHAT(
        route = "chat/{conversationId}",
        label = "Chat",
        icon = Icons.Outlined.Chat,
    ),
    MEMORY(
        route = "memory",
        label = "Memory",
        icon = Icons.Outlined.Psychology,
    ),
    DOCUMENTS(
        route = "documents",
        label = "Documents",
        icon = Icons.Outlined.Description,
    ),
    PLUGINS(
        route = "plugins",
        label = "Plugins",
        icon = Icons.Outlined.Extension,
    ),
    SETTINGS(
        route = "settings",
        label = "Settings",
        icon = Icons.Outlined.Settings,
    );

    companion object {
        fun chatRoute(conversationId: String) = "chat/$conversationId"
    }
}
