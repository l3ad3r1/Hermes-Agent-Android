package com.hermes.agent.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    CONVERSATIONS(route = "conversations", label = "Chats",    icon = Icons.Outlined.Forum),
    CHAT(         route = "chat/{conversationId}", label = "Chat", icon = Icons.Outlined.Chat),
    MEMORY(       route = "memory",       label = "Memory",   icon = Icons.Outlined.Psychology),
    DOCUMENTS(    route = "documents",    label = "Docs",     icon = Icons.Outlined.Description),
    SCHEDULE(     route = "schedule",     label = "Schedule", icon = Icons.Outlined.Schedule),
    SETTINGS(     route = "settings",     label = "Settings", icon = Icons.Outlined.Settings);

    companion object {
        fun chatRoute(conversationId: String) = "chat/$conversationId"
    }
}
