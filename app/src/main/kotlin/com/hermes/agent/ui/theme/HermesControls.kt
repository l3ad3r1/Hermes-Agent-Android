package com.hermes.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable

/**
 * Shared control colors that stay legible in the monochrome (Midnight) theme.
 *
 * Material3's defaults derive the Switch off-state and the OutlinedTextField
 * border from `surfaceVariant` / `outline`, which in a near-black palette are
 * almost invisible against the background. These helpers force a visible
 * mid-grey (`onSurfaceVariant`) so an off switch and an (un)filled field can
 * actually be seen.
 */
@Composable
fun hermesSwitchColors(): SwitchColors {
    val s = MaterialTheme.colorScheme
    return SwitchDefaults.colors(
        checkedThumbColor = s.onPrimary,
        checkedTrackColor = s.primary,
        checkedBorderColor = s.primary,
        uncheckedThumbColor = s.onSurfaceVariant,
        uncheckedTrackColor = s.surface,
        uncheckedBorderColor = s.onSurfaceVariant,
    )
}

@Composable
fun hermesFieldColors(): TextFieldColors {
    val s = MaterialTheme.colorScheme
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = s.onSurface,
        unfocusedTextColor = s.onSurface,
        disabledTextColor = s.onSurfaceVariant,
        focusedBorderColor = s.primary,
        unfocusedBorderColor = s.onSurfaceVariant,
        focusedLabelColor = s.primary,
        unfocusedLabelColor = s.onSurfaceVariant,
        cursorColor = s.primary,
    )
}
