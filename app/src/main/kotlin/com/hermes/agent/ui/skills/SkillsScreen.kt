package com.hermes.agent.ui.skills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.agent.domain.model.Skill
import com.hermes.agent.ui.components.DestructiveActionDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    viewModel: SkillsViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    var pendingDelete by remember { mutableStateOf<Skill?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Skills & Tools") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::showAddDialog) {
                Icon(Icons.Outlined.Add, contentDescription = "Add skill")
            }
        },
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).semantics { liveRegion = LiveRegionMode.Polite },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.listError != null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).semantics { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = "Error: ${state.listError}", color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = viewModel::loadSkills) {
                    Text("Retry")
                }
            }
        } else if (state.skills.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .semantics { contentDescription = "No skills yet. Tap + to add one." },
                contentAlignment = Alignment.Center,
            ) {
                Text("No skills yet. Tap + to add one.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val grouped = state.skills.groupBy { it.category }.toSortedMap()
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                grouped.forEach { (category, skills) ->
                    item {
                        Text(
                            text = category.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(skills, key = { it.id }) { skill ->
                        SkillCard(
                            skill = skill,
                            onClick = { viewModel.viewSkill(skill) },
                            onDelete = { pendingDelete = skill },
                        )
                    }
                }
            }
        }
    }

    if (state.showAddDialog) {
        AddSkillDialog(
            state = state,
            onName = viewModel::setAddName,
            onDescription = viewModel::setAddDescription,
            onContent = viewModel::setAddContent,
            onCategory = viewModel::setAddCategory,
            onTags = viewModel::setAddTags,
            onConfirm = viewModel::saveSkill,
            onDismiss = viewModel::hideAddDialog,
        )
    }

    if (state.showViewDialog && state.selectedSkill != null) {
        SkillViewDialog(
            skill = state.selectedSkill!!,
            onDismiss = viewModel::hideViewDialog,
        )
    }

    pendingDelete?.let { skill ->
        DestructiveActionDialog(
            title = "Delete \"${skill.name}\"?",
            message = "This permanently removes the skill and its instructions. This action cannot be undone.",
            confirmLabel = "Delete skill",
            onConfirm = {
                viewModel.deleteSkill(skill)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkillCard(skill: Skill, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(skill.name, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(8.dp))
                    Badge { Text("v${skill.version}") }
                    if (skill.isBuiltIn) {
                        Spacer(Modifier.width(4.dp))
                        Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                            Text("built-in", color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
                if (skill.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(skill.description, style = MaterialTheme.typography.bodySmall)
                }
                if (skill.tags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        skill.tags.forEach { tag ->
                            AssistChip(onClick = {}, label = { Text(tag, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                }
            }
            if (!skill.isBuiltIn) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete skill")
                }
            }
        }
    }
}

@Composable
private fun SkillViewDialog(skill: Skill, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(skill.name) },
        text = {
            Column(
                modifier = Modifier
                    .height(400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = skill.content,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun AddSkillDialog(
    state: SkillsUiState,
    onName: (String) -> Unit,
    onDescription: (String) -> Unit,
    onContent: (String) -> Unit,
    onCategory: (String) -> Unit,
    onTags: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Skill") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.addName,
                    onValueChange = onName,
                    label = { Text("Name (e.g. research)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.addDescription,
                    onValueChange = onDescription,
                    label = { Text("Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.addCategory,
                    onValueChange = onCategory,
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.addTags,
                    onValueChange = onTags,
                    label = { Text("Tags (comma-separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.addContent,
                    onValueChange = onContent,
                    label = { Text("Instructions (Markdown)") },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.error != null) {
                    Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
