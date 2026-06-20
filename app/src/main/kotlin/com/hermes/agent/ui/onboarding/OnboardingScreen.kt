package com.hermes.agent.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Phase 4 onboarding — Section 8.2 of the plan.
 *
 * Three screens shown once on first launch:
 *   1. Welcome  — brand intro + value prop.
 *   2. Privacy  — on-device-first architecture explanation.
 *   3. Permissions — RECORD_AUDIO + POST_NOTIFICATIONS requests.
 *
 * A "Skip" button is always available; permissions are re-requested
 * on first use if the user declines here.
 */
@Composable
fun OnboardingScreen(
    onCompleted: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val completed by viewModel.completed.collectAsStateWithLifecycle()

    if (completed) {
        onCompleted()
        return
    }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            when (step) {
                OnboardingViewModel.WELCOME -> WelcomeStep()
                OnboardingViewModel.PRIVACY -> PrivacyStep()
                OnboardingViewModel.PERMISSIONS -> PermissionsStep()
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (step < OnboardingViewModel.LAST_STEP) {
                    Button(
                        onClick = viewModel::next,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Continue") }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = viewModel::skip) { Text("Skip onboarding") }
                } else {
                    Button(
                        onClick = viewModel::complete,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Get started") }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = viewModel::back) { Text("Back") }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(48.dp))
        Icon(
            imageVector = Icons.Outlined.AdminPanelSettings,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Hermes Agent",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Your on-device intelligence partner.\n\n" +
                "Hermes runs multi-agent orchestration, function calling, " +
                "and RAG directly on your device — with optional cloud " +
                "fallback for complex tasks.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PrivacyStep() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(48.dp))
        Icon(
            imageVector = Icons.Outlined.PrivacyTip,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Privacy-first by design",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "• Conversations and memories stay on-device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "• Documents ingested into RAG never leave the device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "• Cloud LLM is opt-in. You configure the endpoint and API key.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "• The cloud API key is encrypted at rest via Android Keystore.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "• Backups exclude all conversation and memory data.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun PermissionsStep() {
    val context = LocalContext.current
    var audioGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var notificationsGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> audioGranted = granted }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(48.dp))
        Text(
            text = "Permissions",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(16.dp))
        PermissionRow(
            icon = Icons.Outlined.Mic,
            title = "Microphone",
            rationale = "For voice input. You can decline and use the keyboard.",
            granted = audioGranted,
            onRequest = { audioLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )
        Spacer(modifier = Modifier.height(8.dp))
        PermissionRow(
            icon = Icons.Outlined.Notifications,
            title = "Notifications",
            rationale = "For the notification agent (Phase 3.x). Optional.",
            granted = notificationsGranted,
            onRequest = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        )
    }
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    rationale: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        rationale,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (granted) {
                    Text(
                        "Granted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    OutlinedButton(onClick = onRequest) { Text("Allow") }
                }
            }
        }
    }
}
