package com.hermes.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * The commit control for a card whose fields are saved explicitly, not as you type.
 *
 * Those cards used to save when a field lost focus or the screen closed. Pressing
 * back to dismiss the keyboard leaves the field focused, so an edit could look done
 * while the stored value was still the old one, and a "Test" button then ran against
 * text that was never saved. This says which state the card is in and makes saving
 * a button press.
 */
@Composable
internal fun SaveBar(dirty: Boolean, onSave: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (dirty) "Unsaved changes" else "Saved",
            style = MaterialTheme.typography.bodySmall,
            color = if (dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onSave, enabled = dirty) { Text("Save") }
    }
}
