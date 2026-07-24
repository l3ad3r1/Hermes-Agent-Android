package com.hermes.agent.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.domain.proactive.ProactiveSource

/**
 * Settings → Proactive. Per-capability consent (roadmap v0.12): each source
 * is an explicit opt-in switch, quiet hours and the daily cap are shown, and
 * a source muted by "Less of this" can be restored here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProactiveSettingsScreen(
    onBack: () -> Unit,
    viewModel: ProactiveSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Proactive") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = "Jeeves only pings when a capability below is on, outside quiet hours " +
                    "(${state.quietLabel}), within ${state.dailyCap} pings a day, and never " +
                    "during Do Not Disturb. Every ping — sent or suppressed — is listed under " +
                    "\"What Jeeves did\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            state.sources.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.source.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = when {
                                row.muted -> "Muted by \"Less of this\""
                                row.source == ProactiveSource.SCHEDULED_TASK ->
                                    "Results of your CRON tasks"
                                row.source == ProactiveSource.DIGEST ->
                                    "One morning summary (weather, calendar, todos)"
                                else -> "Reminds you of commitments you made in chat"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (row.muted) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (row.muted) {
                        TextButton(onClick = { viewModel.unmute(row.source) }) { Text("Restore") }
                    }
                    Switch(
                        checked = row.consented,
                        onCheckedChange = { viewModel.setConsent(row.source, it) },
                    )
                }
                HorizontalDivider()
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Notification summary in digest",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Also needs notification access granted to Jeeves in system " +
                            "settings. Captured text appears only in your digest — it is " +
                            "never shown to the AI. Turning this off deletes captured data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.notificationCapture,
                    onCheckedChange = { viewModel.setNotificationCapture(it) },
                )
            }
            val context = LocalContext.current
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                    )
                },
            ) { Text("Open system notification access") }
            HorizontalDivider()
        }
    }
}
