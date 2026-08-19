package com.hermes.agent

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.fragment.app.FragmentActivity
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.ui.navigation.HermesNavGraph
import com.hermes.agent.ui.onboarding.OnboardingScreen
import com.hermes.agent.ui.theme.HermesTheme
import com.hermes.agent.work.OtaUpdateWorker
import com.hermes.agent.core.settings.HermesSettings
import com.hermes.agent.domain.security.DeviceAuthenticationService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single-activity entry point. The Compose nav graph owns the screen
 * hierarchy — see [HermesNavGraph].
 *
 * Phase 4: shows the onboarding flow on first launch, then the main
 * nav graph on subsequent launches.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var deviceAuthenticationService: DeviceAuthenticationService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val onboardingState = MutableStateFlow<Boolean?>(null)

        lifecycleScope.launch {
            onboardingState.value = settings.isOnboardingCompleted()
        }

        handleIntent(intent)
        installDeviceAuthenticationHost()

        setContent {
            val themeMode by HermesSettings.themeModeFlow(this)
                .collectAsState(initial = HermesSettings.themeMode(this))
            val fontFamily by HermesSettings.fontFamilyFlow(this)
                .collectAsState(initial = HermesSettings.fontFamily(this))
            val fontScalePercent by HermesSettings.fontScalePercentFlow(this)
                .collectAsState(initial = HermesSettings.fontScalePercent(this))

            HermesTheme(
                darkTheme = themeMode != HermesSettings.THEME_LIGHT,
                fontFamilyName = fontFamily,
                fontScalePercent = fontScalePercent,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by onboardingState.collectAsState()
                    when (state) {
                        null -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        false -> OnboardingScreen(
                            onCompleted = {
                                onboardingState.value = true
                            },
                        )
                        true -> HermesNavGraph(
                            // Update notification deep-links to Settings → Updates.
                            startAtSettings = intent?.getBooleanExtra(
                                OtaUpdateWorker.EXTRA_OPEN_UPDATES, false,
                            ) == true,
                        )
                    }
                }
            }
        }
    }

    private fun installDeviceAuthenticationHost() {
        var activeRequestId: String? = null
        val prompt = BiometricPrompt(
            this,
            androidx.core.content.ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    activeRequestId?.let { deviceAuthenticationService.submit(it, true) }
                    activeRequestId = null
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    activeRequestId?.let { deviceAuthenticationService.submit(it, false) }
                    activeRequestId = null
                }
            },
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                deviceAuthenticationService.pendingRequest.collect { request ->
                    if (request == null) {
                        if (activeRequestId != null) prompt.cancelAuthentication()
                        activeRequestId = null
                        return@collect
                    }
                    if (request.id == activeRequestId) return@collect
                    activeRequestId = request.id
                    prompt.authenticate(
                        BiometricPrompt.PromptInfo.Builder()
                            .setTitle(request.title)
                            .setSubtitle(request.reason)
                            .setAllowedAuthenticators(
                                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                            )
                            .build(),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent == null) return
        when (intent.action) {
            "com.hermes.agent.action.ASK_HERMES" -> {
                // To open chat directly, we should start the ChatScreen, but it's handled via nav graph.
                // For now, doing nothing leaves it in the MainActivity which opens to the nav graph.
            }
            "com.hermes.agent.action.SHARE_TO_HERMES" -> {
                val shareAction = intent.getStringExtra("EXTRA_SHARE_ACTION")
                val shareText = intent.getStringExtra("EXTRA_SHARE_TEXT")
                // TODO: Handle passing this to the agent / chat screen.
                // For now we just route to ChatScreen via nav graph and maybe pre-fill or send immediately.
            }
            "com.hermes.agent.action.START_VOICE_LISTEN" -> {
                // TODO: Open ChatScreen directly and arm voice listening.
            }
            "com.hermes.agent.action.NOTIFICATION_REPLY" -> {
                val replyText = intent.getStringExtra("EXTRA_REPLY_TEXT")
                // TODO: Send this directly to the agent.
            }
        }
    }
}
