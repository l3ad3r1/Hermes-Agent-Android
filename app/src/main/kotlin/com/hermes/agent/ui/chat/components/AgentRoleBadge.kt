package com.hermes.agent.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.hermes.agent.domain.model.AgentRole

/**
 * Small chip shown above the streaming bubble indicating which agent
 * is currently producing the reply. Phase 2 affordance called out in
 * Section 6.1: "each agent maintains its own context window, tool
 * access permissions, and response formatting preferences."
 */
@Composable
fun AgentRoleBadge(
    role: AgentRole,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = iconFor(role),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(end = 2.dp),
        )
        Text(
            text = role.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private fun iconFor(role: AgentRole): ImageVector = when (role) {
    AgentRole.CONVERSATIONAL -> Icons.Outlined.Forum
    AgentRole.PRODUCTIVITY -> Icons.Outlined.Build
    AgentRole.RESEARCH -> Icons.Outlined.Search
    AgentRole.DEVICE_CONTROL -> Icons.Outlined.PhoneAndroid
    AgentRole.CREATIVE -> Icons.Outlined.Create
}
