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
import android.content.Intent
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.fragment.app.FragmentActivity
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.ui.CrashReportDialog
import com.hermes.agent.ui.chat.PendingChatIntent
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
import androidx.compose.foundation.isSystemInDarkTheme

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

    @Inject
    lateinit var repairReporter: com.hermes.agent.data.diagnostics.RepairReporter

    private val restorePermissions =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { }

    /**
     * A restore brings back everything but Android's runtime grants, and it skips the
     * onboarding step that asks for them: after restoring onto a fresh install every
     * permission was off, so notifications, location weather, contacts and calendar
     * quietly stopped working. The first launch after a restore asks once, for the same
     * permissions onboarding asks for.
     */
    private fun askPermissionsAfterRestore() {
        val restore = com.hermes.agent.data.export.PendingRestore.lastResult(this) ?: return
        if (!restore.ok) return
        val asked = getSharedPreferences("restore_permissions", MODE_PRIVATE)
        if (asked.getLong("asked_for_restore_at", 0L) == restore.at) return
        asked.edit().putLong("asked_for_restore_at", restore.at).apply()
        val wanted = buildList {
            add(android.Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            add(android.Manifest.permission.READ_CONTACTS)
            add(android.Manifest.permission.READ_CALENDAR)
            add(android.Manifest.permission.WRITE_CALENDAR)
            add(android.Manifest.permission.CAMERA)
        }
        val missing = wanted.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) restorePermissions.launch(missing.toTypedArray())
    }

    /** Set by [handleIntent] on cold start (onCreate) or a re-delivered intent (onNewIntent). */
    private var pendingChatIntentTrigger by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val onboardingState = MutableStateFlow<Boolean?>(null)

        lifecycleScope.launch {
            onboardingState.value = settings.isOnboardingCompleted()
        }

        // A recreation (rotation, theme change) hands back the same launch intent;
        // acting on it again opened a second chat or re-armed dictation.
        val freshLaunch = savedInstanceState == null
        if (freshLaunch) handleIntent(intent)
        installDeviceAuthenticationHost()
        if (freshLaunch) askPermissionsAfterRestore()

        setContent {
            val themeMode by HermesSettings.themeModeFlow(this)
                .collectAsState(initial = HermesSettings.themeMode(this))
            val themeStyle by HermesSettings.themeStyleFlow(this)
                .collectAsState(initial = HermesSettings.themeStyle(this))
            val themeAccentColor by HermesSettings.themeAccentColorFlow(this)
                .collectAsState(initial = HermesSettings.themeAccentColor(this))
            val colorPreset by HermesSettings.colorPresetFlow(this)
                .collectAsState(initial = HermesSettings.colorPreset(this))
            val fontFamily by HermesSettings.fontFamilyFlow(this)
                .collectAsState(initial = HermesSettings.fontFamily(this))
            val fontScalePercent by HermesSettings.fontScalePercentFlow(this)
                .collectAsState(initial = HermesSettings.fontScalePercent(this))

            var crashReport by remember { mutableStateOf(com.hermes.agent.data.diagnostics.CrashReporter.pending(this)) }
            HermesTheme(
                // 'System' has to actually follow the system. Testing only against
                // THEME_LIGHT made THEME_SYSTEM -- the default -- resolve to dark
                // forever, so the three-way setting only ever offered two.
                darkTheme = when (themeMode) {
                    HermesSettings.THEME_LIGHT -> false
                    HermesSettings.THEME_DARK -> true
                    else -> isSystemInDarkTheme()
                },
                themeStyle = com.hermes.agent.ui.theme.alt.ThemeStyle.fromStorageKey(themeStyle),
                themeAccentColor = themeAccentColor,
                colorPreset = com.hermes.agent.ui.theme.SeedPreset.fromStorageKey(colorPreset),
                fontFamilyName = fontFamily,
                fontScalePercent = fontScalePercent,
            ) {
                crashReport?.let { report ->
                    CrashReportDialog(
                        report = report,
                        onShare = {
                            startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "Hermes crash report")
                                        putExtra(Intent.EXTRA_TEXT, report)
                                    },
                                    "Share crash report",
                                ),
                            )
                            com.hermes.agent.data.diagnostics.CrashReporter.discard(this)
                            crashReport = null
                        },
                        onDismiss = {
                            com.hermes.agent.data.diagnostics.CrashReporter.discard(this)
                            crashReport = null
                        },
                        onSendForRepair = if (repairReporter.isConfigured) {
                            {
                                val redacted = com.hermes.agent.data.diagnostics.ReportRedactor.redact(report)
                                val firstLine = redacted.lineSequence()
                                    .firstOrNull { it.contains("Exception") || it.contains("Error") } ?: "Crash"
                                lifecycleScope.launch {
                                    val result = repairReporter.file(
                                        title = "Crash: ${firstLine.trim().take(100)}",
                                        body = com.hermes.agent.data.diagnostics.RepairReporter.body(
                                            "Hermes", "Hermes crashed.", redacted, BuildConfig.VERSION_NAME,
                                        ),
                                    )
                                    android.widget.Toast.makeText(
                                        this@MainActivity,
                                        result.fold({ "Report sent for repair." }, { "Could not send: ${it.message}" }),
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                    if (result.isSuccess) com.hermes.agent.data.diagnostics.CrashReporter.discard(this@MainActivity)
                                }
                                crashReport = null
                            }
                        } else {
                            null
                        },
                    )
                }
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
                            startAtSettings = freshLaunch && intent?.getBooleanExtra(
                                OtaUpdateWorker.EXTRA_OPEN_UPDATES, false,
                            ) == true,
                            startPendingChatIntent = pendingChatIntentTrigger,
                            onPendingChatIntentConsumed = { pendingChatIntentTrigger = false },
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
                // Opens to the nav graph's home screen — nothing further to route.
            }
            "com.hermes.agent.action.SHARE_TO_HERMES" -> {
                // EXTRA_SHARE_ACTION (e.g. "summarize", "explain") is not yet used to
                // pick a persona/prompt template — the shared text is sent as-is.
                val shareText = intent.getStringExtra("EXTRA_SHARE_TEXT")
                if (!shareText.isNullOrBlank()) {
                    PendingChatIntent.publish(PendingChatIntent.Action.PrefillText(shareText))
                    pendingChatIntentTrigger = true
                }
            }
            "com.hermes.agent.action.START_VOICE_LISTEN" -> {
                PendingChatIntent.publish(PendingChatIntent.Action.ArmVoiceListen)
                pendingChatIntentTrigger = true
            }
            "com.hermes.agent.action.NOTIFICATION_REPLY" -> {
                // Inline replies come as RemoteInput results; the old receiver path used the extra.
                val replyText = androidx.core.app.RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence("KEY_REPLY")?.toString()
                    ?: intent.getStringExtra("EXTRA_REPLY_TEXT")
                // Clears the reply spinner; the conversation continues in the app.
                val notificationId = intent.getIntExtra("EXTRA_NOTIFICATION_ID", 0)
                if (notificationId != 0) {
                    androidx.core.app.NotificationManagerCompat.from(this).cancel(notificationId)
                }
                if (!replyText.isNullOrBlank()) {
                    PendingChatIntent.publish(PendingChatIntent.Action.PrefillText(replyText))
                    pendingChatIntentTrigger = true
                }
            }
        }
    }
}
