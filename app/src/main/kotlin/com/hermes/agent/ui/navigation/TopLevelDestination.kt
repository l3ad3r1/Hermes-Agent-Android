package com.hermes.agent.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.ui.graphics.vector.ImageVector

enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    CONVERSATIONS(route = "conversations",        label = "Chats",      icon = Icons.Outlined.Forum),
    CHAT(         route = "chat/{conversationId}", label = "Chat",       icon = Icons.Outlined.Chat),
    SKILLS(       route = "skills",               label = "Skills",     icon = Icons.Outlined.Stars),
    CONNECT(      route = "connect",              label = "Connect",    icon = Icons.Outlined.Link),
    SCHEDULE(     route = "schedule",             label = "Schedule",   icon = Icons.Outlined.Schedule),
    DELEGATE(     route = "delegate",             label = "Delegate",   icon = Icons.AutoMirrored.Outlined.Send),
    EXPERIMENT(   route = "experiment",           label = "Experiment", icon = Icons.Outlined.Science),
    SETTINGS(     route = "settings",             label = "Settings",   icon = Icons.Outlined.Settings),
    MEMORY(       route = "memory",               label = "Memory",     icon = Icons.Outlined.Psychology);

    companion object {
        fun chatRoute(conversationId: String) = "chat/$conversationId"
    }
}
