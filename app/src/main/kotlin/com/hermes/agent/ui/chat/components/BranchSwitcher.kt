package com.hermes.agent.ui.chat.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.agent.data.chat.BranchInfo

/**
 * `‹ 2 / 3 ›` above the message where the chat went more than one way, so an edited or re-run
 * turn no longer erases what was there before: step back and forth between the versions.
 */
@Composable
fun BranchSwitcher(info: BranchInfo, onSwitch: (BranchInfo, Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onSwitch(info, info.position - 1) },
            enabled = info.position > 1,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Previous version")
        }
        Text(
            "${info.position} / ${info.count}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = { onSwitch(info, info.position + 1) },
            enabled = info.position < info.count,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Next version")
        }
    }
}
