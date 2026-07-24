package com.hermes.agent.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.ui.graphics.vector.ImageVector

enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME(         route = "home",                 label = "Home",       icon = Icons.Outlined.Home),
    CONVERSATIONS(route = "conversations",         label = "Chats",      icon = Icons.Outlined.Chat),
    CHAT(         route = "chat/{conversationId}", label = "Chat",       icon = Icons.Outlined.Chat),
    DOCUMENTS(    route = "documents",             label = "Artifacts",      icon = Icons.Outlined.Description),
    SKILLS(       route = "skills",               label = "Skills & Tools", icon = Icons.Outlined.Stars),
    CONNECT(      route = "connect",              label = "Messaging",      icon = Icons.Outlined.Link),
    SCHEDULE(     route = "schedule",             label = "CRON",           icon = Icons.Outlined.Schedule),
    DELEGATE(     route = "delegate",             label = "Delegate",   icon = Icons.AutoMirrored.Outlined.Send),
    EXPERIMENT(   route = "experiment",           label = "Experiment", icon = Icons.Outlined.Science),
    SETTINGS(     route = "settings",             label = "Settings",   icon = Icons.Outlined.Settings),
    MEMORY(       route = "memory",               label = "Memory",     icon = Icons.Outlined.Psychology),
    KANBAN(       route = "kanban",               label = "Board",      icon = Icons.Outlined.ViewColumn),
    TICKET(       route = "ticket/{ticketId}",     label = "Ticket",     icon = Icons.Outlined.ViewColumn);

    companion object {
        fun chatRoute(conversationId: String) = "chat/$conversationId"
        fun ticketRoute(ticketId: String) = "ticket/$ticketId"
    }
}
