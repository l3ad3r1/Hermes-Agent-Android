package com.hermes.agent.ui.voice

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun TalkScreen(
    onBack: () -> Unit,
    viewModel: TalkViewModel = hiltViewModel(),
    conversationId: String = java.util.UUID.randomUUID().toString(),
) {
    TalkScreen(
        onBack = onBack,
        controller = viewModel.controller,
        conversationId = conversationId,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalkScreen(
    onBack: () -> Unit,
    controller: TalkSessionController,
    conversationId: String = java.util.UUID.randomUUID().toString(),
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val transcript by controller.transcript.collectAsStateWithLifecycle()
    val reply by controller.assistantReply.collectAsStateWithLifecycle()
    val isBt by controller.isBluetoothConnected.collectAsStateWithLifecycle()
    val error by controller.error.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) controller.startSession(conversationId) }

    LaunchedEffect(Unit) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) controller.startSession(conversationId)
        else micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    DisposableEffect(Unit) {
        onDispose {
            controller.stopSession()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Talk Mode") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Exit Talk Mode")
                    }
                },
                actions = {
                    if (isBt) {
                        Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = "Bluetooth Headset Connected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Status & state indicator
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val stateText = when (state) {
                    TalkState.IDLE -> "Standing by"
                    TalkState.LISTENING -> "Listening to you..."
                    TalkState.THINKING -> "Thinking..."
                    TalkState.SPEAKING -> "Hermes is speaking (Barge-in active)"
                }
                val stateColor = when (state) {
                    TalkState.IDLE -> MaterialTheme.colorScheme.outline
                    TalkState.LISTENING -> MaterialTheme.colorScheme.primary
                    TalkState.THINKING -> MaterialTheme.colorScheme.tertiary
                    TalkState.SPEAKING -> MaterialTheme.colorScheme.secondary
                }

                Text(
                    text = stateText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = stateColor,
                )

                if (isBt) {
                    Text(
                        text = "Routing audio through Bluetooth headset",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Animated Orb / Visualizer
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(200.dp),
            ) {
                val orbColor = when (state) {
                    TalkState.IDLE -> MaterialTheme.colorScheme.surfaceVariant
                    TalkState.LISTENING -> MaterialTheme.colorScheme.primaryContainer
                    TalkState.THINKING -> MaterialTheme.colorScheme.tertiaryContainer
                    TalkState.SPEAKING -> MaterialTheme.colorScheme.secondaryContainer
                }
                val orbScale = if (state == TalkState.LISTENING || state == TalkState.SPEAKING) pulseScale else 1.0f

                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .scale(orbScale)
                        .background(orbColor, CircleShape),
                )
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Live conversation transcripts
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (transcript.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                    ) {
                        Text(
                            text = "You: $transcript",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                if (reply.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        )
                    ) {
                        Text(
                            text = "Hermes: $reply",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state == TalkState.SPEAKING) {
                    FilledTonalButton(
                        onClick = { controller.triggerBargeIn() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Interrupt")
                    }
                }

                Button(
                    onClick = {
                        controller.stopSession()
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("End Session")
                }
            }
        }
    }
}
