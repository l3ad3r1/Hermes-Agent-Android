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

class TaskerPluginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialConfig = TaskerBundleHelper.fromIntent(intent)

        setContent {
            HermesTheme {
                TaskerPluginConfigScreen(
                    initialConfig = initialConfig,
                    onSave = { config ->
                        val resultIntent = Intent().apply {
                            putExtra(TaskerBundleHelper.EXTRA_BUNDLE, config.toBundle())
                            putExtra(TaskerBundleHelper.EXTRA_BLURB, config.toBlurb())
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
        }
    }
}
