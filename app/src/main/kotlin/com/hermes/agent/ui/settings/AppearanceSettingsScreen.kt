package com.hermes.agent.ui.settings
import com.hermes.agent.domain.settings.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.core.settings.HermesSettings
import com.hermes.agent.core.theme.IbmPlexSans
import com.hermes.agent.core.theme.Rubik
import kotlin.math.roundToInt

/** 85%–130% in 5% detents: ten stops, so eight between the two ends. */
private const val FONT_SCALE_STEPS = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val themeStyle by viewModel.themeStyle.collectAsStateWithLifecycle()
    val fontFamily by viewModel.fontFamily.collectAsStateWithLifecycle()
    val fontScalePercent by viewModel.fontScalePercent.collectAsStateWithLifecycle()
    val darkMode = themeMode != HermesSettings.THEME_LIGHT

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
                navigationIcon = {
                    Surface(
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp).size(44.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Navigate back",
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppearanceCard {
                Text("Monochrome", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (darkMode) "OLED black · white · grayscale" else "Pure white · black · grayscale",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(82.dp)
                        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(22.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
                        .padding(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.fillMaxWidth(0.7f).height(9.dp)
                                .background(MaterialTheme.colorScheme.onBackground, CircleShape),
                        )
                        Box(
                            Modifier.fillMaxWidth(0.45f).height(7.dp)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                        )
                        Box(
                            Modifier.fillMaxWidth().height(20.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                        )
                    }
                }
            }

            AppearanceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Dark Mode", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Use true-black OLED surfaces",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = darkMode,
                        onCheckedChange = { enabled ->
                            viewModel.setThemeMode(
                                if (enabled) HermesSettings.THEME_DARK else HermesSettings.THEME_LIGHT,
                            )
                        },
                    )
                }
            }

            AppearanceCard {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choose a colour style for Hermes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(
                        ThemeStyleOption(
                            HermesSettings.THEME_STYLE_CLASSIC, "Classic",
                            "Hermes's own default look",
                            null,
                        ),
                        ThemeStyleOption(
                            HermesSettings.THEME_STYLE_MYBRAIN, "My Brain",
                            "Cyan and violet, in the style of the My Brain app",
                            listOf(Color(0xFF28B0DF), Color(0xFF5F12CA)),
                        ),
                        ThemeStyleOption(
                            HermesSettings.THEME_STYLE_MATERIAL_YOU, "Material You",
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                "Colours drawn from your wallpaper"
                            } else {
                                "Requires Android 12 or newer"
                            },
                            null,
                            enabled = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S,
                        ),
                    ).forEach { option ->
                        ThemeStyleRow(
                            option = option,
                            selected = themeStyle == option.value,
                            onClick = { viewModel.setThemeStyle(option.value) },
                        )
                    }
                }
            }

            AppearanceCard {
                Text("Font", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choose the typeface used across Hermes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                // A list rather than a row of initials: each row can render the
                // font's own name plus a sample line in that typeface, which is
                // what actually lets you tell the faces apart before choosing.
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(
                        FontOption(HermesSettings.FONT_GEIST, "Geist", null),
                        FontOption(HermesSettings.FONT_SYSTEM, "Sans Serif", FontFamily.SansSerif),
                        FontOption(HermesSettings.FONT_SERIF, "Serif", FontFamily.Serif),
                        FontOption(HermesSettings.FONT_MONO, "Monospace", FontFamily.Monospace),
                        FontOption(HermesSettings.FONT_RUBIK, "Rubik", Rubik),
                        FontOption(HermesSettings.FONT_IBM_PLEX, "IBM Plex Sans", IbmPlexSans),
                    ).forEach { option ->
                        FontListRow(
                            label = option.label,
                            selected = fontFamily == option.value,
                            onClick = { viewModel.setFontFamily(option.value) },
                            fontFamily = option.previewFamily,
                        )
                    }
                }
            }

            AppearanceCard {
                // Dragging is previewed locally and only committed when the
                // finger lifts: each commit writes to prefs and rebuilds the
                // app-wide typography, which is far too costly to run on every
                // pixel of a drag.
                var pending by remember(fontScalePercent) {
                    mutableFloatStateOf(fontScalePercent.toFloat())
                }
                Text("Font size", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${pending.roundToInt()}% · applies throughout the app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("A", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = pending,
                        onValueChange = { pending = it },
                        onValueChangeFinished = {
                            viewModel.setFontScalePercent(pending.roundToInt())
                        },
                        valueRange = HermesSettings.MIN_FONT_SCALE_PERCENT.toFloat()..
                            HermesSettings.MAX_FONT_SCALE_PERCENT.toFloat(),
                        // One detent per 5%, so the slider lands on round numbers.
                        steps = FONT_SCALE_STEPS,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    )
                    Text("A", style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    "The quick brown fox",
                    // Previews the value under the finger rather than the saved
                    // one, so the sample resizes while dragging.
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize *
                            (pending / fontScalePercent.toFloat()),
                    ),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun AppearanceCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

private data class FontOption(
    val value: String,
    val label: String,
    val previewFamily: FontFamily?,
)

@Composable
private fun FontListRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    fontFamily: FontFamily? = null,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = fontFamily,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                )
                Text(
                    "The quick brown fox jumps over the lazy dog",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = fontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private data class ThemeStyleOption(
    val value: String,
    val label: String,
    val description: String,
    val swatches: List<Color>?,
    val enabled: Boolean = true,
)

/**
 * One row per colour style: swatches for the two fixed palettes, a live
 * preview of the current scheme's own primary for Classic, and a disabled
 * state (with the reason in the description) when Material You is not
 * available on this OS version.
 */
@Composable
private fun ThemeStyleRow(
    option: ThemeStyleOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = option.enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                val swatches = option.swatches ?: listOf(MaterialTheme.colorScheme.primary)
                swatches.forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(c, CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (option.enabled) {
                        LocalContentColor.current
                    } else {
                        LocalContentColor.current.copy(alpha = 0.5f)
                    },
                )
                Text(
                    option.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "Selected", modifier = Modifier.size(20.dp))
            }
        }
    }
}
