package com.hermes.agent.ui.settings
import com.hermes.agent.domain.settings.*

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.BuildConfig
import com.hermes.agent.R
import com.hermes.agent.ui.theme.hermesSwitchColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About, permissions & security") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(text = "App permissions")
            PermissionsCard()

            SectionHeader(text = "Companion apps")
            CompanionAppsCard()

            SectionHeader(text = stringResource(R.string.settings_section_security))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    InfoRow(
                        title = stringResource(R.string.settings_keystore_status),
                        value = "Hardware-backed (Android Keystore)",
                    )
                }
            }
            ExpandableCard(
                title = "Security audit",
                subtitle = securityAuditSummary,
            ) {
                SecurityAuditRows()
            }

            SectionHeader(text = stringResource(R.string.settings_section_about))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoRow(title = "Application", value = "Hermes")
                    InfoRow(
                        title = stringResource(R.string.settings_app_version),
                        value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    )
                    InfoRow(title = "Build type", value = BuildConfig.BUILD_TYPE)
                }
            }

            if (BuildConfig.OTA_ENABLED) {
                SectionHeader(text = "Updates")
                UpdateSection(
                    state = updateState,
                    canInstall = viewModel.canInstallPackages(),
                    onCheck = viewModel::checkForUpdate,
                    onDownload = viewModel::downloadAndInstall,
                    onManagePermission = viewModel::promptInstallPermission,
                    onOpenUrl = { url ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        viewModel.dismissUpdateState()
                    },
                    onDismiss = viewModel::dismissUpdateState,
                )
            }
        }
    }
}

// --- Permissions ---------------------------------------------------------------

/**
 * A user-facing permission the app can use. [runtimePermission] is set for the
 * ones granted through a system dialog; the rest are "special access" toggled on
 * their own settings screen. [isGranted]/[grantIntent] resolve the real state
 * and destination — the `requestedPermissionsFlags` bit is unreliable for
 * special-access grants (e.g. All files access), which is why each entry checks
 * the actual platform API instead.
 */
private class PermissionEntry(
    val label: String,
    val description: String,
    val manifestName: String,
    val runtimePermission: String? = null,
    val isGranted: (Context) -> Boolean,
    val grantIntent: (Context) -> Intent,
)

private fun appDetailsIntent(context: Context) = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.fromParts("package", context.packageName, null),
)

private fun runtimeGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private val PERMISSION_ENTRIES: List<PermissionEntry> = listOf(
    PermissionEntry(
        "Microphone", "Voice input and hands-free Talk mode.",
        "android.permission.RECORD_AUDIO", "android.permission.RECORD_AUDIO",
        { runtimeGranted(it, "android.permission.RECORD_AUDIO") }, ::appDetailsIntent,
    ),
    PermissionEntry(
        "Camera", "Let the agent take or attach photos.",
        "android.permission.CAMERA", "android.permission.CAMERA",
        { runtimeGranted(it, "android.permission.CAMERA") }, ::appDetailsIntent,
    ),
    PermissionEntry(
        "Show notifications", "Replies, task results, and proactive nudges.",
        "android.permission.POST_NOTIFICATIONS", "android.permission.POST_NOTIFICATIONS",
        {
            Build.VERSION.SDK_INT < 33 ||
                runtimeGranted(it, "android.permission.POST_NOTIFICATIONS")
        },
        ::appDetailsIntent,
    ),
    PermissionEntry(
        "Precise location", "Ambient presence and location-aware answers.",
        "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_FINE_LOCATION",
        { runtimeGranted(it, "android.permission.ACCESS_FINE_LOCATION") }, ::appDetailsIntent,
    ),
    PermissionEntry(
        "Read contacts", "Resolve names when calling or messaging.",
        "android.permission.READ_CONTACTS", "android.permission.READ_CONTACTS",
        { runtimeGranted(it, "android.permission.READ_CONTACTS") }, ::appDetailsIntent,
    ),
    PermissionEntry(
        "Calendar", "Read and create calendar events.",
        "android.permission.READ_CALENDAR", "android.permission.READ_CALENDAR",
        { runtimeGranted(it, "android.permission.READ_CALENDAR") }, ::appDetailsIntent,
    ),
    PermissionEntry(
        "Send SMS", "Send text messages you ask the agent to send.",
        "android.permission.SEND_SMS", "android.permission.SEND_SMS",
        { runtimeGranted(it, "android.permission.SEND_SMS") }, ::appDetailsIntent,
    ),
    PermissionEntry(
        "All files access", "Save downloaded models into a folder you can browse.",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        isGranted = { Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager() },
        grantIntent = {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", it.packageName, null),
            )
        },
    ),
    PermissionEntry(
        "Draw over other apps", "Show the assistant overlay on top of other apps.",
        "android.permission.SYSTEM_ALERT_WINDOW",
        isGranted = { Settings.canDrawOverlays(it) },
        grantIntent = {
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", it.packageName, null),
            )
        },
    ),
    PermissionEntry(
        "Modify system settings", "Change brightness, volume, and similar device settings.",
        "android.permission.WRITE_SETTINGS",
        isGranted = { Settings.System.canWrite(it) },
        grantIntent = {
            Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.fromParts("package", it.packageName, null),
            )
        },
    ),
    PermissionEntry(
        "Ignore battery optimisation", "Keep the background heartbeat running reliably.",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        isGranted = {
            (it.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(it.packageName)
        },
        grantIntent = { Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) },
    ),
    PermissionEntry(
        "Install unknown apps", "Install OTA updates downloaded from GitHub.",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        isGranted = { it.packageManager.canRequestPackageInstalls() },
        grantIntent = {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.fromParts("package", it.packageName, null),
            )
        },
    ),
    PermissionEntry(
        "Do Not Disturb control", "Let the agent silence or restore notifications.",
        "android.permission.ACCESS_NOTIFICATION_POLICY",
        isGranted = {
            (it.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .isNotificationPolicyAccessGranted
        },
        grantIntent = { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
    ),
)

@Composable
private fun PermissionsCard() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    val declaredNames: Set<String> = remember {
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.toSet()
                ?: emptySet()
        }.getOrDefault(emptySet())
    }
    val entries = remember(declaredNames) {
        PERMISSION_ENTRIES.filter { entry ->
            entry.manifestName in declaredNames &&
                (entry.manifestName != "android.permission.MANAGE_EXTERNAL_STORAGE" || Build.VERSION.SDK_INT >= 30)
        }
    }

    val requestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh++ }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh++ }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            entries.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                val granted = remember(refresh, entry) { entry.isGranted(context) }
                PermissionToggleRow(
                    label = entry.label,
                    description = entry.description,
                    granted = granted,
                    onToggle = {
                        when {
                            granted -> settingsLauncher.launch(appDetailsIntent(context))
                            entry.runtimePermission != null ->
                                requestLauncher.launch(entry.runtimePermission)
                            else -> settingsLauncher.launch(entry.grantIntent(context))
                        }
                    },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                "Toggling opens the system screen where the grant is made — Android never lets an app change its own permissions directly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun PermissionToggleRow(
    label: String,
    description: String,
    granted: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = granted,
            onCheckedChange = { onToggle() },
            colors = hermesSwitchColors(),
        )
    }
}

// --- Companion apps ----------------------------------------------------------

private const val TERMUX_PKG = "com.termux"
private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

@Composable
private fun CompanionAppsCard() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    fun installed(pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0); true
    }.getOrDefault(false)

    fun openOrGet(pkg: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            context.startActivity(launch)
        } else {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/en/packages/$pkg/")),
            )
        }
        refresh++
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            CompanionToggleRow(
                "Termux",
                "Local Linux shell — powers the termux tool (packages, python, git).",
                remember(refresh) { installed(TERMUX_PKG) },
                { openOrGet(TERMUX_PKG) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            CompanionToggleRow(
                "Shizuku",
                "ADB-privileged bridge — lets the shell tool run with elevated privileges.",
                remember(refresh) { installed(SHIZUKU_PKG) },
                { openOrGet(SHIZUKU_PKG) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                "On → opens the app. Off → opens its F-Droid page to install. Uninstall from the launcher.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun CompanionToggleRow(
    name: String,
    subtitle: String,
    isInstalled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = isInstalled,
            onCheckedChange = { onToggle() },
            colors = hermesSwitchColors(),
        )
    }
}
