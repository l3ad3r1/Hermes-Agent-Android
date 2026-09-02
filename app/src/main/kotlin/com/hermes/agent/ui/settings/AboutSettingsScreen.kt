package com.hermes.agent.ui.settings
import com.hermes.agent.domain.settings.*

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.BuildConfig
import com.hermes.agent.R

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
            SecurityAuditPanel()

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

// --- Permissions -----------------------------------------------------------

/** Runtime permissions the app can request with a system dialog. */
private val RUNTIME_PERMS = setOf(
    "android.permission.RECORD_AUDIO",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.SEND_SMS",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.READ_CONTACTS",
    "android.permission.READ_CALENDAR",
    "android.permission.WRITE_CALENDAR",
    "android.permission.CAMERA",
)

/** Special access toggled only on its own system screen. name -> settings action. */
private val SPECIAL_PERMS = mapOf(
    "android.permission.WRITE_SETTINGS" to Settings.ACTION_MANAGE_WRITE_SETTINGS,
    "android.permission.SYSTEM_ALERT_WINDOW" to Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
    "android.permission.MANAGE_EXTERNAL_STORAGE" to Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
    "android.permission.REQUEST_INSTALL_PACKAGES" to Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
    "android.permission.ACCESS_NOTIFICATION_POLICY" to Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
)

private val FRIENDLY = mapOf(
    "RECORD_AUDIO" to "Microphone",
    "POST_NOTIFICATIONS" to "Show notifications",
    "SEND_SMS" to "Send SMS",
    "ACCESS_FINE_LOCATION" to "Precise location",
    "ACCESS_COARSE_LOCATION" to "Approximate location",
    "ACCESS_BACKGROUND_LOCATION" to "Location in background",
    "READ_CONTACTS" to "Read contacts",
    "READ_CALENDAR" to "Read calendar",
    "WRITE_CALENDAR" to "Write calendar",
    "CAMERA" to "Camera",
    "WRITE_SETTINGS" to "Modify system settings",
    "SYSTEM_ALERT_WINDOW" to "Draw over other apps",
    "MANAGE_EXTERNAL_STORAGE" to "All files access",
    "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to "Ignore battery optimisation",
    "REQUEST_INSTALL_PACKAGES" to "Install unknown apps (for OTA updates)",
    "ACCESS_NOTIFICATION_POLICY" to "Do Not Disturb control",
    "INTERNET" to "Internet",
    "ACCESS_NETWORK_STATE" to "Network state",
    "FOREGROUND_SERVICE" to "Run foreground services",
    "FOREGROUND_SERVICE_DATA_SYNC" to "Background data-sync service",
    "RECEIVE_BOOT_COMPLETED" to "Start on boot",
    "WAKE_LOCK" to "Keep device awake for tasks",
    "WRITE_EXTERNAL_STORAGE" to "Write to shared storage (legacy)",
)

@Composable
private fun PermissionsCard() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    val requestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh++ }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh++ }

    val declared: List<Pair<String, Boolean>> = remember(refresh) {
        runCatching {
            val info: PackageInfo = context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_PERMISSIONS,
            )
            val names = info.requestedPermissions ?: emptyArray()
            val flags = info.requestedPermissionsFlags ?: IntArray(names.size)
            names.mapIndexed { i, name ->
                name to (flags.getOrElse(i) { 0 } and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0)
            }.filter { it.first.startsWith("android.permission.") }
                .sortedBy { FRIENDLY[it.first.removePrefix("android.permission.")] ?: it.first }
        }.getOrDefault(emptyList())
    }

    fun openAppInfo() {
        settingsLauncher.launch(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            declared.forEachIndexed { index, (name, granted) ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                val short = name.removePrefix("android.permission.")
                val label = FRIENDLY[short] ?: short.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
                val runtime = name in RUNTIME_PERMS
                val specialAction = SPECIAL_PERMS[name]

                val action: () -> Unit = when {
                    !granted && runtime -> ({ requestLauncher.launch(name) })
                    !granted && specialAction != null -> ({
                        settingsLauncher.launch(
                            Intent(specialAction, Uri.fromParts("package", context.packageName, null)),
                        )
                    })
                    else -> ::openAppInfo
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = action)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (granted) "Granted — tap to manage in system settings"
                            else if (runtime || specialAction != null) "Not granted — tap to grant"
                            else "Not granted",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (granted) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!granted && (runtime || specialAction != null)) {
                        TextButton(onClick = action) { Text("Grant") }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                "Android only lets an app change permissions from its system settings page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

// --- Companion apps ------------------------------------------------------

private const val TERMUX_PKG = "com.termux"
private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

@Composable
private fun CompanionAppsCard() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    fun installed(pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0); true
    }.getOrDefault(false)

    fun open(pkg: String) {
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it) }
    }

    fun fdroid(pkg: String) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/en/packages/$pkg/")),
        )
        refresh++
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            CompanionRow(
                "Termux",
                "Local Linux shell — powers the termux tool (packages, python, git).",
                installed(TERMUX_PKG), { open(TERMUX_PKG) }, { fdroid(TERMUX_PKG) },
                refresh,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            CompanionRow(
                "Shizuku",
                "ADB-privileged bridge — lets the shell tool run with elevated privileges.",
                installed(SHIZUKU_PKG), { open(SHIZUKU_PKG) }, { fdroid(SHIZUKU_PKG) },
                refresh,
            )
        }
    }
}

@Composable
private fun CompanionRow(
    name: String,
    subtitle: String,
    isInstalled: Boolean,
    onOpen: () -> Unit,
    onGet: () -> Unit,
    @Suppress("UNUSED_PARAMETER") refreshKey: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(0.dp))
        if (isInstalled) {
            TextButton(onClick = onOpen) { Text("Installed · Open") }
        } else {
            TextButton(onClick = onGet) { Text("Get from F-Droid") }
        }
    }
}
