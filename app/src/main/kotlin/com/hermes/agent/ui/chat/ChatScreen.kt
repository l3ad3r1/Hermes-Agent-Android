package com.hermes.agent.ui.chat

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountTree
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.hermes.agent.ui.theme.GeistMono

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
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var planDrawerOpen by remember { mutableStateOf(false) }
    var chatTab by remember { mutableStateOf(0) } // 0=Tools, 1=Terminal, 2=Subagents

    // Auto-scroll to bottom when new items arrive.
    LaunchedEffect(uiState.visibleItems.size, uiState.streamingText) {
        if (uiState.visibleItems.isNotEmpty()) {
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
            modifier = Modifier.imePadding(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = uiState.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                    actions = {
                        if (uiState.currentPlan != null) {
                            IconButton(onClick = { planDrawerOpen = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.AccountTree,
                                    contentDescription = "View execution plan",
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                androidx.compose.foundation.layout.Column {
                    ChatInputBar(
                        isSending = uiState.isSending,
                        isListening = uiState.isListening,
                        onSend = viewModel::sendMessage,
                        onCancel = viewModel::cancel,
                        onMicToggle = viewModel::toggleVoiceInput,
                        prefillText = uiState.inputPrefill,
                    )
                    ChatStatusBar(
                        estimatedTokens = uiState.estimatedTokens,
                        activeModel = uiState.activeModel,
                        isOnDevice = uiState.isOnDevice,
                    )
                }
            },
        ) { innerPadding ->
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                ChatModeTabs(selected = chatTab, onSelect = { chatTab = it })
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    when (chatTab) {
                        1 -> TerminalPanel()
                        else -> {
                            if (uiState.messages.isEmpty() && uiState.streamingText == null) {
                                EmptyChatState(modifier = Modifier.fillMaxSize())
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
                    }
                }
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

@Composable
private fun EmptyChatState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.chat_empty_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.chat_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ChatStatusBar(
    estimatedTokens: Int,
    activeModel: String,
    isOnDevice: Boolean,
) {
    androidx.compose.foundation.layout.Row(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = if (isOnDevice) "on-device" else "cloud",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (activeModel.isNotBlank()) {
            Text(
                text = activeModel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = if (estimatedTokens > 0) "~${estimatedTokens}t" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
    val runner = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            com.hermes.agent.ui.terminal.TerminalEntryPoint::class.java,
        ).termuxCommandRunner()
    }
    fun toast(msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
    fun launchInTermux(command: String) {
        if (!runner.launchSession(command)) {
            toast("Termux isn't installed (or unreachable). Install Termux from F-Droid first.")
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
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.Button(
            onClick = {
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
            },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) { Text("Install Hermes in Termux") }
        Spacer(Modifier.height(10.dp))
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
        Text(
            "Requires Termux (F-Droid) with allow-external-apps=true in ~/.termux/termux.properties.",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = GeistMono,
            color = scheme.outline,
        )
    }
}

