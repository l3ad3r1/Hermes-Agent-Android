package com.hermes.agent.ui.onboarding

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.ui.components.BlinkingCursor
import com.hermes.agent.ui.components.HermesDiamond
import com.hermes.agent.ui.theme.Geist
import com.hermes.agent.ui.theme.GeistMono
import com.hermes.agent.ui.theme.HermesTerminalBg
import com.hermes.agent.ui.theme.HermesTerminalText

/**
 * Onboarding hero — a single welcome screen matching the Nous design:
 * the brand diamond, value prop, a `$ hermes connect` terminal chip, and
 * two CTAs. The primary CTA requests runtime permissions (mic + notifications)
 * before entering the app; either CTA completes onboarding. Permissions are
 * re-requested on first use if declined here.
 */
@Composable
fun OnboardingScreen(
    onCompleted: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val completed by viewModel.completed.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.complete() }

    if (completed) {
        onCompleted()
        return
    }

    val scheme = MaterialTheme.colorScheme
    val accentSoft = scheme.primary.copy(alpha = 0.15f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .background(
                Brush.radialGradient(
                    colors = listOf(accentSoft, scheme.background),
                    radius = 900f,
                )
            )
            .padding(horizontal = 26.dp, vertical = 30.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top eyebrow
            Text(
                text = "OPEN SOURCE · MIT",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.outline,
                fontFamily = GeistMono,
                letterSpacing = 2.6.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            // Center hero
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                HermesDiamond(tileSize = 62.dp, glyphSize = 22.dp)
                Spacer(Modifier.height(22.dp))
                Text(
                    text = "The agent that\ngrows with you",
                    fontFamily = Geist,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    lineHeight = 36.sp,
                    letterSpacing = (-0.03).sp,
                    color = scheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "One agent, one memory, every surface. Now in your pocket.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 280.dp),
                )
                Spacer(Modifier.height(24.dp))
                // Terminal chip
                Row(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(HermesTerminalBg)
                        .border(1.dp, scheme.outline.copy(alpha = 0.25f), MaterialTheme.shapes.medium)
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$ ",
                        fontFamily = GeistMono,
                        fontSize = 12.5.sp,
                        color = scheme.outline,
                    )
                    Text(
                        text = "hermes connect",
                        fontFamily = GeistMono,
                        fontSize = 12.5.sp,
                        color = HermesTerminalText,
                    )
                    Spacer(Modifier.padding(start = 3.dp))
                    BlinkingCursor(color = HermesTerminalText)
                }
            }

            // CTAs
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Button(
                    onClick = {
                        val perms = buildList {
                            add(android.Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray()
                        permissionLauncher.launch(perms)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.primary,
                        contentColor = scheme.onPrimary,
                    ),
                ) {
                    Text("Connect Nous Portal", fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp)
                }
                OutlinedButton(
                    onClick = { viewModel.complete() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        "Continue without account",
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = scheme.onBackground,
                    )
                }
                Text(
                    text = "v0.4.7 · Nous Research",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = GeistMono,
                    color = scheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }
    }
}
