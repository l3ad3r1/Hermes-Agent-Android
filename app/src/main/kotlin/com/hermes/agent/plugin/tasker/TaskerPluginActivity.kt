package com.hermes.agent.plugin.tasker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.ui.theme.HermesTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TaskerPluginActivity : ComponentActivity() {

    @Inject
    lateinit var hostAuthority: TaskerHostAuthority

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialConfig = TaskerBundleHelper.fromIntent(intent)

        // Tasker launches this with startActivityForResult, so unlike the fire
        // broadcast the caller IS identifiable here. This is the only point in
        // the plugin protocol where the user can be shown who is asking.
        val hostPackage = callingActivity?.packageName ?: callingPackage

        setContent {
            HermesTheme {
                if (hostPackage == null) {
                    // Reached without startActivityForResult — no caller to name,
                    // so there is nothing the user could meaningfully approve.
                    UnknownHostScreen(
                        onClose = {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        },
                    )
                    return@HermesTheme
                }

                val label = remember(hostPackage) { hostAuthority.label(hostPackage) }
                val fingerprint = remember(hostPackage) { hostAuthority.signingCertificate(hostPackage) }
                var approved by remember(hostPackage) {
                    mutableStateOf(hostAuthority.isApproved(hostPackage))
                }

                if (!approved) {
                    HostApprovalScreen(
                        label = label,
                        packageName = hostPackage,
                        fingerprint = fingerprint,
                        onApprove = {
                            approved = hostAuthority.approve(hostPackage) != null
                        },
                        onCancel = {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        },
                    )
                    return@HermesTheme
                }

                TaskerPluginConfigScreen(
                    initialConfig = initialConfig,
                    hostLabel = label,
                    onRevokeHost = {
                        hostAuthority.revoke(hostPackage)
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                    onSave = { config ->
                        // The token travels in the configuration Tasker stores and
                        // sends back on every fire; it is what the receiver checks.
                        val token = hostAuthority.tokenFor(hostPackage)
                        if (token == null) {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                            return@TaskerPluginConfigScreen
                        }
                        val authorized = config.copy(hostToken = token)
                        val resultIntent = Intent().apply {
                            putExtra(TaskerBundleHelper.EXTRA_BUNDLE, authorized.toBundle())
                            putExtra(TaskerBundleHelper.EXTRA_BLURB, authorized.toBlurb())
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    },
                    onCancel = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskerPluginConfigScreen(
    initialConfig: TaskerBundleHelper.TaskerConfig,
    onSave: (TaskerBundleHelper.TaskerConfig) -> Unit,
    onCancel: () -> Unit,
    hostLabel: String = "",
    onRevokeHost: () -> Unit = {},
) {
    var selectedRole by remember { mutableStateOf(initialConfig.agentRole) }
    var promptTemplate by remember { mutableStateOf(initialConfig.promptTemplate) }
    var timeoutSeconds by remember { mutableIntStateOf(initialConfig.timeoutSeconds) }
    var speakResponse by remember { mutableStateOf(initialConfig.speakResponse) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    val roles = AgentRole.entries

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tasker Hermes Action") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Select Agent", style = MaterialTheme.typography.titleMedium)

                    ExposedDropdownMenuBox(
                        expanded = dropdownExpanded,
                        onExpandedChange = { dropdownExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedRole.displayName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Agent") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                        )
                        ExposedDropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false },
                        ) {
                            roles.forEach { role ->
                                DropdownMenuItem(
                                    text = { Text(role.displayName) },
                                    onClick = {
                                        selectedRole = role
                                        dropdownExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Prompt Template", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Enter the prompt for Hermes. You can use Tasker variables like %BATT or %TIME.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = promptTemplate,
                        onValueChange = { promptTemplate = it },
                        label = { Text("Prompt") },
                        placeholder = { Text("e.g. Battery is at %BATT%, optimize power") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Speak Response aloud", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Read agent response via TTS upon completion",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = speakResponse,
                            onCheckedChange = { speakResponse = it },
                        )
                    }
                }
            }

            Button(
                onClick = {
                    onSave(
                        TaskerBundleHelper.TaskerConfig(
                            agentRole = selectedRole,
                            promptTemplate = promptTemplate.trim(),
                            timeoutSeconds = timeoutSeconds,
                            speakResponse = speakResponse,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = promptTemplate.isNotBlank(),
            ) {
                Text("Save Action")
            }

            if (hostLabel.isNotBlank()) {
                Text(
                    "$hostLabel is allowed to run Hermes tasks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Withdrawing mints nothing and clears the stored token, so every
                // action this host already saved stops firing immediately.
                TextButton(
                    onClick = onRevokeHost,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Stop allowing $hostLabel", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * Shown the first time an automation app configures a Hermes action, and again
 * whenever that app's signing certificate stops matching what was approved.
 *
 * The fingerprint is on screen because the package name alone is not identity:
 * a package name can be squatted, a signing certificate cannot be forged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostApprovalScreen(
    label: String,
    packageName: String,
    fingerprint: String?,
    onApprove: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Allow $label?") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "$label wants to run Hermes agent tasks on your behalf. Anything it " +
                    "triggers runs with the same access you have in the app — including " +
                    "tools that read and change things on this phone.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("App", style = MaterialTheme.typography.labelMedium)
                    Text(packageName, style = MaterialTheme.typography.bodySmall)
                    Text("Signing certificate (SHA-256)", style = MaterialTheme.typography.labelMedium)
                    Text(
                        fingerprint ?: "Unavailable — the app could not be inspected",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text(
                "Only allow this if you installed $label yourself and recognise it. " +
                    "You can withdraw it later from this screen, and Hermes withdraws it " +
                    "automatically if the app is ever replaced by a build from a different signer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                ) { Text("Not now") }
                Button(
                    onClick = onApprove,
                    enabled = fingerprint != null,
                    modifier = Modifier.weight(1f),
                ) { Text("Allow") }
            }
        }
    }
}

/**
 * Fallback when the activity is opened without `startActivityForResult`, which
 * leaves no caller to identify. Configuring in that state would produce a token
 * bound to nobody.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnknownHostScreen(onClose: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Tasker Hermes Action") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Open this from your automation app instead.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Hermes could not tell which app opened this screen, so there is nothing " +
                    "it can ask you to approve. Add the Hermes action from inside Tasker " +
                    "(or whichever automation app you use) and this screen will open with " +
                    "that app's identity attached.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}
