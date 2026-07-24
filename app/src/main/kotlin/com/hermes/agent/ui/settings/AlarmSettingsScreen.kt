package com.hermes.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sassybutler.alarm.VoiceCatalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val alarmSettings by viewModel.alarmSettings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Daybook") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            AlarmSettingsSection(
                state = alarmSettings,
                voices = viewModel.voiceOptions,
                onHonorific = viewModel::setHonorific,
                onSassLevel = viewModel::setSassLevel,
                onSnoozeMinutes = viewModel::setSnoozeMinutes,
                onVoiceEnabled = viewModel::setVoiceEnabled,
                onBirdsIntro = viewModel::setBirdsIntro,
                onSnoozeCommentary = viewModel::setSnoozeCommentary,
                onHaptics = viewModel::setHaptics,
                onVoiceName = viewModel::setVoiceName,
                onBriefingCalendar = viewModel::setBriefingCalendar,
                onBriefingWeather = viewModel::setBriefingWeather,
                onBriefingTodos = viewModel::setBriefingTodos,
                onBriefingNotes = viewModel::setBriefingNotes,
                onBriefingHeadlines = viewModel::setBriefingHeadlines,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmSettingsSection(
    state: AlarmSettings,
    voices: List<VoiceCatalog.Voice>,
    onHonorific: (String) -> Unit,
    onSassLevel: (Int) -> Unit,
    onSnoozeMinutes: (Int) -> Unit,
    onVoiceEnabled: (Boolean) -> Unit,
    onBirdsIntro: (Boolean) -> Unit,
    onSnoozeCommentary: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onVoiceName: (String) -> Unit,
    onBriefingCalendar: (Boolean) -> Unit,
    onBriefingWeather: (Boolean) -> Unit,
    onBriefingTodos: (Boolean) -> Unit,
    onBriefingNotes: (Boolean) -> Unit,
    onBriefingHeadlines: (Boolean) -> Unit,
) {
    var voiceMenuOpen by remember { mutableStateOf(false) }
    val honorifics = listOf("Sir", "Madam", "Boss")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text(text = "Address me as", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    honorifics.forEachIndexed { index, name ->
                        SegmentedButton(
                            selected = state.honorific == name,
                            onClick = { onHonorific(name) },
                            shape = SegmentedButtonDefaults.itemShape(index, honorifics.size),
                        ) { Text(name) }
                    }
                }
            }

            Column {
                Text(text = "Sass level: ${state.sassLevel}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "How sharp the wake-up remark is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = state.sassLevel.toFloat(),
                    onValueChange = { onSassLevel(it.toInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.semantics {
                        contentDescription = "Sass level"
                        stateDescription = "${state.sassLevel} out of 100"
                    },
                )
            }

            Column {
                Text(
                    text = "Snooze: ${state.snoozeMinutes} min",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Slider(
                    value = state.snoozeMinutes.toFloat(),
                    onValueChange = { onSnoozeMinutes(it.toInt().coerceAtLeast(1)) },
                    valueRange = 1f..30f,
                    modifier = Modifier.semantics {
                        contentDescription = "Snooze duration"
                        stateDescription = "${state.snoozeMinutes} minutes"
                    },
                )
            }

            ToggleRow(
                title = "Spoken wake-up",
                subtitle = "Use the on-device butler voice. Off: birdsong only.",
                checked = state.voiceEnabled,
                onCheckedChange = onVoiceEnabled,
            )

            if (state.voiceEnabled) {
                Box {
                    NavRow(
                        icon = Icons.Outlined.RecordVoiceOver,
                        title = "Voice",
                        subtitle = state.voiceLabel,
                        onClick = { voiceMenuOpen = true },
                    )
                    DropdownMenu(
                        expanded = voiceMenuOpen,
                        onDismissRequest = { voiceMenuOpen = false },
                    ) {
                        voices.forEach { voice ->
                            DropdownMenuItem(
                                text = { Text(voice.label) },
                                onClick = {
                                    onVoiceName(voice.name)
                                    voiceMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }

            ToggleRow(
                title = "Birdsong intro",
                subtitle = "Play birds before the spoken greeting.",
                checked = state.birdsIntro,
                onCheckedChange = onBirdsIntro,
            )
            ToggleRow(
                title = "Comment on snooze",
                subtitle = "The butler has opinions about your snoozing.",
                checked = state.snoozeCommentary,
                onCheckedChange = onSnoozeCommentary,
            )
            ToggleRow(
                title = "Haptics",
                subtitle = "Vibrate while the alarm sounds.",
                checked = state.haptics,
                onCheckedChange = onHaptics,
            )

            SectionHeader("Morning Briefing Sections")

            ToggleRow(
                title = "Calendar",
                subtitle = "Include today's calendar events.",
                checked = state.briefingCalendar,
                onCheckedChange = onBriefingCalendar,
            )
            ToggleRow(
                title = "Weather",
                subtitle = "Include the current weather forecast.",
                checked = state.briefingWeather,
                onCheckedChange = onBriefingWeather,
            )
            ToggleRow(
                title = "Pending Todos",
                subtitle = "Include unfinished tasks.",
                checked = state.briefingTodos,
                onCheckedChange = onBriefingTodos,
            )
            ToggleRow(
                title = "Recent Notes",
                subtitle = "Include notes modified in the last 24h.",
                checked = state.briefingNotes,
                onCheckedChange = onBriefingNotes,
            )
            ToggleRow(
                title = "Top Headlines",
                subtitle = "Include a few global news headlines.",
                checked = state.briefingHeadlines,
                onCheckedChange = onBriefingHeadlines,
            )
        }
    }
}
