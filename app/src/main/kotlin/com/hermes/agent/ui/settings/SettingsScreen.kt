package com.hermes.agent.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.BuildConfig
import com.hermes.agent.R
import com.hermes.agent.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_settings)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // --- Appearance section ---
            SectionHeader(text = "Appearance")

            ThemePicker(
                currentTheme = settings.appTheme,
                onThemeSelected = viewModel::setAppTheme,
            )

            // --- Inference section ---
            SectionHeader(text = stringResource(R.string.settings_section_inference))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleRow(
                        title = stringResource(R.string.settings_on_device_enabled),
                        subtitle = stringResource(R.string.settings_on_device_enabled_subtitle),
                        checked = settings.onDeviceEnabled,
                        onCheckedChange = viewModel::setOnDeviceEnabled,
                    )
                    HorizontalDivider()
                    Text(
                        text = "On-device model: ${settings.onDeviceModel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Idle unload (minutes): ${settings.idleUnloadMinutes}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = settings.idleUnloadMinutes.toFloat(),
                        onValueChange = { viewModel.setIdleUnloadMinutes(it.toInt()) },
                        valueRange = 1f..30f,
                        steps = 28,
                    )
                }
            }

            // --- Cloud section ---
            SectionHeader(text = stringResource(R.string.settings_section_cloud))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleRow(
                        title = stringResource(R.string.settings_cloud_enabled),
                        subtitle = stringResource(R.string.settings_cloud_enabled_subtitle),
                        checked = settings.cloudEnabled,
                        onCheckedChange = viewModel::setCloudEnabled,
                    )
                    HorizontalDivider()

                    var apiKey by remember(settings.cloudApiKey) { mutableStateOf(settings.cloudApiKey) }
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            viewModel.setCloudApiKey(it)
                        },
                        label = { Text(stringResource(R.string.settings_cloud_api_key)) },
                        supportingText = {
                            Text(stringResource(R.string.settings_cloud_api_key_subtitle))
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    var baseUrl by remember(settings.cloudBaseUrl) { mutableStateOf(settings.cloudBaseUrl) }
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            viewModel.setCloudBaseUrl(it)
                        },
                        label = { Text(stringResource(R.string.settings_cloud_base_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    var model by remember(settings.cloudModel) { mutableStateOf(settings.cloudModel) }
                    OutlinedTextField(
                        value = model,
                        onValueChange = {
                            model = it
                            viewModel.setCloudModel(it)
                        },
                        label = { Text(stringResource(R.string.settings_cloud_model)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        text = stringResource(R.string.settings_complexity_threshold),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = settings.complexityThreshold,
                        onValueChange = viewModel::setComplexityThreshold,
                        valueRange = 0f..1f,
                    )
                }
            }

            // --- Security section ---
            SectionHeader(text = stringResource(R.string.settings_section_security))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow(
                        title = stringResource(R.string.settings_knox_status),
                        value = if (viewModel.isKnoxAvailable) "Available" else "Not available on this device",
                    )
                    HorizontalDivider()
                    InfoRow(
                        title = stringResource(R.string.settings_keystore_status),
                        value = "Hardware-backed (Android Keystore)",
                    )
                }
            }

            // --- About ---
            SectionHeader(text = stringResource(R.string.settings_section_about))
            SecurityAuditPanel()
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoRow(title = "Application", value = "Hermes Agent")
                    InfoRow(title = stringResource(R.string.settings_app_version), value = BuildConfig.VERSION_NAME)
                    InfoRow(title = "Build type", value = BuildConfig.BUILD_TYPE)
                    InfoRow(title = "Phase", value = "4 (Polish & Launch)")
                }
            }
        }
    }
}

private data class ThemeOption(
    val name: String,
    val label: String,
    val bg: Color,
    val fg: Color,
    val accent: Color,
)

private val themeOptions = listOf(
    ThemeOption("MIDNIGHT",    "Midnight",     Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFF444444)),
    ThemeOption("PAPER",       "Paper",        Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFFEEEEEE)),
    ThemeOption("HERMES_BLUE", "Hermes Blue",  Color(0xFF3300FF), Color(0xFFFFFFFF), Color(0xFF2200CC)),
)

@Composable
private fun ThemePicker(
    currentTheme: String,
    onThemeSelected: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "App Theme",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                themeOptions.forEach { option ->
                    ThemeSwatch(
                        option = option,
                        selected = currentTheme == option.name,
                        onClick = { onThemeSelected(option.name) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeSwatch(
    option: ThemeOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(2.dp, borderColor, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
        ) {
            Surface(
                modifier = Modifier.matchParentSize(),
                color = option.bg,
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(8.dp),
                        color = option.fg,
                        shape = RoundedCornerShape(4.dp),
                        content = {},
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(6.dp),
                        color = option.fg.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp),
                        content = {},
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp),
                        color = option.accent,
                        shape = RoundedCornerShape(4.dp),
                        content = {},
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = option.fg,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(16.dp),
                )
            }
        }
        Text(
            text = option.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
