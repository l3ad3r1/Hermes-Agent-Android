package com.hermes.agent.ui.chat.components
import com.hermes.agent.domain.settings.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hermes.agent.domain.model.EvidenceState

@Composable
fun EvidenceStateBadge(
    state: EvidenceState,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when (state) {
        EvidenceState.PREPARED -> Color(0xFFF57C00) // Orange
        EvidenceState.RUNNING -> Color(0xFF1976D2) // Blue
        EvidenceState.VERIFIED -> Color(0xFF388E3C) // Green
        EvidenceState.REPORTED_DONE -> Color(0xFF757575) // Grey
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = state.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White
        )
    }
}
