package com.hermes.agent.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hermes.agent.R
import com.hermes.agent.ui.chat.components.ChatInputBar
import com.hermes.agent.ui.chat.components.MessageBubble
import com.hermes.agent.ui.chat.components.StreamingBubble
import com.hermes.agent.ui.components.PulsingDot
import com.hermes.agent.core.theme.GeistMono

/**
 * Main chat screen. Renders the message list, the streaming bubble (when
 * active), the input bar, and surfaces errors via a Snackbar.
 *
 * Phase 2 additions:
 *   - Modal drawer showing the current execution plan + step status.
 *   - Streaming bubble now includes agent-role badge + tool-call cards.
 *
 * The list auto-scrolls to the bottom whenever a new message or streaming
 * token arrives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    onNewChat: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var planDrawerOpen by remember { mutableStateOf(false) }
    val pendingConfirmation by viewModel.pendingToolConfirmation.collectAsStateWithLifecycle()

    // Auto-scroll only when the user is already near the bottom of the list.
    // This prevents pulling users away from earlier content they're reading.
    val isNearBottom = remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= (uiState.visibleItems.lastIndex - 1).coerceAtLeast(0)
        }
    }
    LaunchedEffect(uiState.visibleItems.size, uiState.streamingText) {
        if (uiState.visibleItems.isNotEmpty() && isNearBottom.value) {
            listState.animateScrollToItem(uiState.visibleItems.lastIndex)
        }
    }

    // Surface errors as a Snackbar.
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissError()
        }
    }

    ModalNavigationDrawer(
        drawerContent = {
            PlanDrawer(
                plan = uiState.currentPlan,
                onClose = { planDrawerOpen = false },
            )
        },
        drawerState = rememberDrawerState(planDrawerOpen),
    ) {
        Scaffold(
            topBar = {
                ReferenceChatTopBar(
                    onOpenChats = onBack,
                    onNewChat = onNewChat,
                    onOpenPlan = if (uiState.currentPlan != null) {
                        { planDrawerOpen = true }
                    } else null,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                // Wrapped in a Surface so the bar's colour fills edge-to-edge
                // behind the transparent system navigation bar (no black strip),
                // while navigationBarsPadding lifts the input above the nav bar.
                // imePadding here (not on the Scaffold) consumes the nav-bar inset
                // first, so keyboard + nav insets don't double-count.
                Surface(color = MaterialTheme.colorScheme.background) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            .navigationBarsPadding()
                            .imePadding(),
                    ) {
                        uiState.pendingClarification?.let { req ->
                            ClarificationCard(
                                request = req,
                                onAnswer = viewModel::answerClarification,
                            )
                        }
                        ChatInputBar(
                            isSending = uiState.isSending,
                            isListening = uiState.isListening,
                            onSend = viewModel::sendMessage,
                            onCancel = viewModel::cancel,
                            onMicToggle = viewModel::toggleVoiceInput,
                            prefillText = uiState.inputPrefill,
                        )
                    }
                }
            },
        ) { innerPadding ->
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (uiState.todos.isNotEmpty()) {
                    TodoPanel(todos = uiState.todos)
                }
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    if (uiState.messages.isEmpty() && uiState.streamingText == null) {
                        EmptyChatState(
                            onPromptSelected = viewModel::sendMessage,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                vertical = 12.dp,
                            ),
                        ) {
                            items(uiState.visibleItems) { item ->
                                when (item) {
                                    is ChatListItem.MessageItem -> MessageBubble(message = item.message)
                                    is ChatListItem.StreamingItem -> StreamingBubble(item = item)
                                }
                            }
                            item { Spacer(modifier = Modifier.height(8.dp)) }
                        }
                    }
                }
            } // closes Column
        } // closes Scaffold
    } // closes ModalNavigationDrawer

    pendingConfirmation?.let { pending ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.submitToolConfirmation(pending.id, false) },
            title = { Text("Approve Action") },
            text = {
                Column {
                    Text("Hermes wants to run the following tool:")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = pending.call.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = pending.call.arguments.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.submitToolConfirmation(pending.id, true) }) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.submitToolConfirmation(pending.id, false) }) {
                    Text("Deny")
                }
            }
        )
    }
}

@Composable
private fun ReferenceChatTopBar(
    onOpenChats: () -> Unit,
    onNewChat: () -> Unit,
    onOpenPlan: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onOpenChats,
            modifier = Modifier.align(Alignment.CenterStart).size(52.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Menu, contentDescription = "Open chats")
            }
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onOpenPlan ?: onOpenChats)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Chat",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (onOpenPlan != null) "View execution plan" else "Open chats",
                modifier = Modifier.size(22.dp),
            )
        }
        Surface(
            onClick = onNewChat,
            modifier = Modifier.align(Alignment.CenterEnd).size(52.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "New chat")
            }
        }
    }
}
@Composable
private fun PlanDrawer(
    plan: PlanSummary?,
    onClose: () -> Unit,
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Execution Plan",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (plan == null) {
                Text(
                    text = "No active plan.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            plan.steps.forEachIndexed { i, step ->
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(
                                text = step.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${step.agentRole.displayName} · ${step.status.name.lowercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    selected = i == plan.currentStepIndex,
                    onClick = onClose,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Surfaced above the input bar when the agent's `clarify` tool is waiting on
 * the user. Predefined choices render as tappable buttons; a free-text field
 * covers the open-ended case. Answering resumes the suspended tool — note we
 * route through [ChatViewModel.answerClarification], not the normal input bar,
 * so we don't start a second turn while one is mid-flight.
 */
@Composable
private fun ClarificationCard(
    request: ClarificationRequest,
    onAnswer: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var freeText by remember(request.question) { mutableStateOf("") }
    androidx.compose.material3.Surface(
        color = scheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "Hermes needs a quick answer",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = GeistMono,
                color = scheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = request.question,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = scheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            request.choices.forEach { choice ->
                androidx.compose.material3.OutlinedButton(
                    onClick = { onAnswer(choice) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(choice, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(
                    value = freeText,
                    onValueChange = { freeText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(if (request.choices.isEmpty()) "Type your answer…" else "Or type another answer…")
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                Spacer(Modifier.size(8.dp))
                androidx.compose.material3.Button(
                    onClick = {
                        if (freeText.isNotBlank()) {
                            onAnswer(freeText)
                            freeText = ""
                        }
                    },
                    enabled = freeText.isNotBlank(),
                    shape = MaterialTheme.shapes.medium,
                ) { Text("Send") }
            }
        }
    }
}

/**
 * Compact, collapsible checklist showing the agent's live `todo` plan so the
 * user can track progress. Driven by [ChatUiState.todos] (backed by TodoStore).
 */
@Composable
private fun TodoPanel(todos: List<TodoItem>) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(true) }
    val done = todos.count { it.status == "completed" }
    androidx.compose.material3.Surface(
        color = scheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            ) {
                Text(
                    "PLAN",
                    fontFamily = GeistMono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "$done/${todos.size}",
                    fontFamily = GeistMono,
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    todos.forEach { item -> TodoRow(item) }
                }
            }
        }
    }
}

@Composable
private fun TodoRow(item: TodoItem) {
    val scheme = MaterialTheme.colorScheme
    val (glyph, color) = when (item.status) {
        "completed" -> "✓" to scheme.tertiary
        "in_progress" -> "▸" to scheme.primary
        "cancelled" -> "✗" to scheme.onSurfaceVariant.copy(alpha = 0.5f)
        else -> "○" to scheme.onSurfaceVariant
    }
    val dim = item.status == "completed" || item.status == "cancelled"
    Row(verticalAlignment = Alignment.Top) {
        Text(glyph, fontFamily = GeistMono, fontSize = 13.sp, color = color)
        Spacer(Modifier.size(8.dp))
        Text(
            item.content,
            style = MaterialTheme.typography.bodyMedium,
            color = if (dim) scheme.onSurfaceVariant.copy(alpha = 0.6f) else scheme.onSurface,
        )
    }
}

@Composable
private fun EmptyChatState(
    onPromptSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prompts = listOf(
        QuickPrompt(Icons.Outlined.Today, "Plan my day", "Help me plan my day"),
        QuickPrompt(Icons.Outlined.Description, "Create a note", "Create a note for me"),
        QuickPrompt(Icons.Outlined.Public, "Look something up", "Look something up for me"),
    )
    Column(
        modifier = modifier.padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        prompts.forEach { prompt ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onPromptSelected(prompt.prompt) }
                    .padding(horizontal = 10.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = prompt.icon,
                    contentDescription = null,
                    modifier = Modifier.size(25.dp),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.size(18.dp))
                Text(
                    text = prompt.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

private data class QuickPrompt(
    val icon: ImageVector,
    val label: String,
    val prompt: String,
)
@Composable
private fun rememberDrawerState(open: Boolean): androidx.compose.material3.DrawerState {
    val state = androidx.compose.material3.rememberDrawerState(
        initialValue = if (open) androidx.compose.material3.DrawerValue.Open
        else androidx.compose.material3.DrawerValue.Closed,
    )
    LaunchedEffect(open) {
        if (open) state.open() else state.close()
    }
    return state
}

// ── Tools / Terminal / Subagents segmented control + panels ───────────

private val chatModeLabels = listOf("Chat", "Terminal")

@Composable
private fun ChatModeTabs(selected: Int, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.small)
            .background(scheme.surfaceVariant)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        chatModeLabels.forEachIndexed { i, label ->
            val active = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) scheme.primary else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontFamily = GeistMono,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TerminalPanel() {
    val scheme = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val entryPoint = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            com.hermes.agent.ui.terminal.TerminalEntryPoint::class.java,
        )
    }
    val runner = remember { entryPoint.termuxCommandRunner() }
    val settings = remember { entryPoint.settingsRepository() }
    // null = not yet known. Seed from the persisted flag for an immediate correct
    // UI, then re-verify by probing Termux for the `hermes` CLI in the background.
    var hermesInstalled by remember { mutableStateOf<Boolean?>(null) }
    var probing by remember { mutableStateOf(false) }

    // Tri-state probe: only a definitive answer updates state + the persisted
    // flag. A timeout / plugin error / missing permission is INCONCLUSIVE and
    // must not clobber a previous "installed" verdict (that's why the install
    // button used to reappear even after a successful install).
    suspend fun probeHermes() {
        if (probing || !runner.isTermuxInstalled()) return
        probing = true
        val result = runner.run(
            "command -v hermes >/dev/null 2>&1 && echo __HERMES_OK__ || echo __HERMES_NO__",
            timeoutMs = 20_000,
        )
        when {
            result.contains("__HERMES_OK__") -> {
                hermesInstalled = true
                settings.setTermuxHermesInstalled(true)
            }
            result.contains("__HERMES_NO__") -> {
                hermesInstalled = false
                settings.setTermuxHermesInstalled(false)
            }
            else -> Unit // inconclusive — keep the previous verdict
        }
        probing = false
    }

    LaunchedEffect(Unit) {
        hermesInstalled = settings.current().termuxHermesInstalled
        val permGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, runner.runCommandPermission,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (permGranted) probeHermes()
    }
    fun toast(msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()

    fun doLaunch(command: String) {
        runner.launchSession(command)?.let { toast(it) }
    }

    // Pending action while the RUN_COMMAND permission dialog is up:
    // a command to launch, or "" meaning re-run the install probe.
    var pendingCommand by remember { mutableStateOf<String?>(null) }
    val permLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val cmd = pendingCommand
        pendingCommand = null
        when {
            cmd == null -> Unit
            !granted -> toast("Termux permission denied. Grant \"Run commands in Termux\" to continue.")
            cmd.isEmpty() -> scope.launch { probeHermes() }
            else -> doLaunch(cmd)
        }
    }

    fun checkInstallation() {
        if (!runner.isTermuxInstalled()) {
            toast("Termux not found. Install Termux from F-Droid (not the Play Store build).")
            return
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, runner.runCommandPermission,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            scope.launch { probeHermes() }
        } else {
            pendingCommand = "" // sentinel: probe after grant
            permLauncher.launch(runner.runCommandPermission)
        }
    }

    fun launchInTermux(command: String) {
        if (!runner.isTermuxInstalled()) {
            toast("Termux not found. Install Termux from F-Droid (not the Play Store build).")
            return
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, runner.runCommandPermission,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            doLaunch(command)
        } else {
            pendingCommand = command
            permLauncher.launch(runner.runCommandPermission)
        }
    }

    fun installHermes() {
        scope.launch {
            val script = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.assets.open("install-hermes-termux.sh")
                        .bufferedReader().use { it.readText() }
                }.getOrNull()
            }
            if (script == null) toast("Couldn't read installer script.")
            else launchInTermux(script)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulsingDot(color = scheme.tertiary, size = 6.dp)
            Spacer(Modifier.size(8.dp))
            Text(
                "Termux bridge",
                fontFamily = GeistMono,
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Run the full Hermes Agent in a real Linux environment via the Termux app. " +
                "Install once, then start it — each action opens a Termux session you can watch.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        // Installation status: verified by probing Termux for the hermes CLI.
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (statusText, statusColor) = when {
                probing -> "Hermes CLI: checking…" to scheme.onSurfaceVariant
                hermesInstalled == true -> "Hermes CLI: installed ✓" to scheme.tertiary
                hermesInstalled == false -> "Hermes CLI: not installed" to scheme.onSurfaceVariant
                else -> "Hermes CLI: unknown" to scheme.onSurfaceVariant
            }
            Text(
                statusText,
                fontFamily = GeistMono,
                fontSize = 12.sp,
                color = statusColor,
            )
            Spacer(Modifier.weight(1f))
            if (!probing) {
                Text(
                    "Check",
                    fontFamily = GeistMono,
                    fontSize = 12.sp,
                    color = scheme.primary,
                    modifier = Modifier
                        .clickable { checkInstallation() }
                        .padding(4.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        // The installer button disappears once the Hermes CLI is verified
        // installed; a "Reinstall" affordance remains at the bottom.
        if (hermesInstalled != true) {
            androidx.compose.material3.Button(
                onClick = { installHermes() },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) { Text("Install Hermes in Termux") }
            Spacer(Modifier.height(10.dp))
        }
        androidx.compose.material3.OutlinedButton(
            onClick = { launchInTermux("hermes; echo; read -p 'press enter to close…' _") },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) { Text("Run hermes") }
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.OutlinedButton(
            onClick = { launchInTermux("echo 'Termux ready.'; bash") },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) { Text("Open Termux shell") }
        Spacer(Modifier.weight(1f))
        if (hermesInstalled == true) {
            Text(
                "Hermes CLI detected · Reinstall",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = GeistMono,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { installHermes() }
                    .padding(vertical = 4.dp),
            )
        }
        Text(
            "Requires Termux (F-Droid) with allow-external-apps=true in ~/.termux/termux.properties.",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = GeistMono,
            color = scheme.outline,
        )
    }
}

