package com.hermes.agent.ui.settings

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.core.settings.HermesSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
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
                Text("Font", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choose the typeface used across Hermes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    listOf(
                        FontOption(HermesSettings.FONT_GEIST, "Geist", null),
                        FontOption(HermesSettings.FONT_SYSTEM, "Sans", FontFamily.SansSerif),
                        FontOption(HermesSettings.FONT_SERIF, "Serif", FontFamily.Serif),
                        FontOption(HermesSettings.FONT_MONO, "Mono", FontFamily.Monospace),
                    ).forEach { option ->
                        CircleChoice(
                            label = option.label,
                            selected = fontFamily == option.value,
                            onClick = { viewModel.setFontFamily(option.value) },
                            fontFamily = option.previewFamily,
                        )
                    }
                }
            }

            AppearanceCard {
                Text("Font size", style = MaterialTheme.typography.titleMedium)
                Text(
                    "$fontScalePercent% · applies throughout the app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    listOf(85 to "S", 100 to "M", 115 to "L", 130 to "XL").forEach { (size, label) ->
                        CircleChoice(
                            label = label,
                            selected = fontScalePercent == size,
                            onClick = { viewModel.setFontScalePercent(size) },
                        )
                    }
                }
                Text(
                    "The quick brown fox",
                    style = MaterialTheme.typography.bodyLarge,
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
private fun CircleChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    fontFamily: FontFamily? = null,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(66.dp),
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    label.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Bold,
                )
                if (label.length > 1) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = fontFamily,
                    )
                }
                if (selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}
