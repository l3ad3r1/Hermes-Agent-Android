package com.hermes.agent.ui.onboarding

import android.Manifest
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.hermes.agent.data.device.DeviceProfile
import com.hermes.agent.ui.components.HermesDiamond
import com.hermes.agent.core.theme.Geist
import com.hermes.agent.core.theme.GeistMono

/**
 * Multi-step setup journey: welcome → profile → device scan.
 * Collected profile + scanned device capabilities are committed to the agent's
 * memory on finish (see [OnboardingViewModel]).
 */
@Composable
fun OnboardingScreen(
    onCompleted: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val completed by viewModel.completed.collectAsStateWithLifecycle()

    if (completed) {
        onCompleted()
        return
    }

    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .background(
                Brush.radialGradient(
                    colors = listOf(scheme.primary.copy(alpha = 0.13f), scheme.background),
                    radius = 1000f,
                )
            )
            .padding(horizontal = 24.dp, vertical = 26.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            StepDots(current = step, total = OnboardingViewModel.DEVICE + 1)
            Spacer(Modifier.height(18.dp))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    OnboardingViewModel.WELCOME -> WelcomeStep()
                    OnboardingViewModel.PROFILE -> ProfileStep(viewModel)
                    OnboardingViewModel.PERMISSIONS -> PermissionsStep()
                    else -> DeviceStep(viewModel)
                }
            }
            Spacer(Modifier.height(16.dp))
            NavBar(step = step, viewModel = viewModel)
        }
    }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .width(if (i == current) 22.dp else 10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= current) scheme.primary else scheme.surfaceVariant),
            )
        }
    }
}

@Composable
private fun WelcomeStep() {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HermesDiamond(tileSize = 62.dp, glyphSize = 22.dp)
        Spacer(Modifier.height(22.dp))
        Text(
            "Let's set up\nyour assistant",
            fontFamily = Geist,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            lineHeight = 36.sp,
            color = scheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "A few details, the permissions Hermes needs, and a quick look at your phone — " +
                "all saved to memory so the agent knows you and your device.",
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
    }
}

@Composable
private fun ProfileStep(viewModel: OnboardingViewModel) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    StepHeader("About you", "Hermes commits these to memory so it can act on your behalf.")
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(76.dp)) // clear the header
        Field("Name", profile.name) { v -> viewModel.update { it.copy(name = v) } }
        Field("Home address", profile.address) { v -> viewModel.update { it.copy(address = v) } }
        Field("Phone number", profile.phone, KeyboardType.Phone) { v -> viewModel.update { it.copy(phone = v) } }
        Field("Email", profile.email, KeyboardType.Email) { v -> viewModel.update { it.copy(email = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                Field("Wake time (e.g. 07:00)", profile.wakeTime) { v -> viewModel.update { it.copy(wakeTime = v) } }
            }
            Box(Modifier.weight(1f)) {
                Field("Sleep time (e.g. 23:00)", profile.sleepTime) { v -> viewModel.update { it.copy(sleepTime = v) } }
            }
        }
        Field("Anything else Hermes should know", profile.notes, singleLine = false) { v ->
            viewModel.update { it.copy(notes = v) }
        }
        Spacer(Modifier.height(8.dp))
    }
}



@Composable
private fun DeviceStep(viewModel: OnboardingViewModel) {
    val scheme = MaterialTheme.colorScheme
    val device by viewModel.device.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    StepHeader("Your device", "Hermes checks your phone's capabilities so it can tailor its features to your device.")
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(76.dp))
        when {
            scanning -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
                Text("Scanning hardware…", color = scheme.onSurfaceVariant)
            }
            error != null && device == null -> Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Scan failed: $error",
                    color = scheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.scanDevice() },
                    shape = MaterialTheme.shapes.medium
                ) { Text("Retry scan") }
            }
            device == null -> Button(
                onClick = { viewModel.scanDevice() },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) { Text("Scan this device") }
            else -> DeviceCard(device!!)
        }
    }
}

@Composable
private fun DeviceCard(d: DeviceProfile) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(scheme.surface)
            .border(1.dp, scheme.outline.copy(alpha = 0.25f), MaterialTheme.shapes.medium)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        SpecRow("Device", "${d.manufacturer} ${d.model}")
        SpecRow("Android", "${d.androidRelease} (API ${d.sdkInt})")
        SpecRow("Chipset", d.soc)
        SpecRow("CPU", "${d.cpuCores} cores · ${d.abi}")
        SpecRow("RAM", "${oneDp(d.totalRamGb)} GB")
        SpecRow("Storage", "${oneDp(d.freeStorageGb)} GB free / ${oneDp(d.totalStorageGb)} GB")
        SpecRow("GPU", d.gpuRenderer.ifBlank { "unknown" })
        SpecRow("Display", d.screen)
        if (d.batteryPct in 0..100) SpecRow("Battery", "${d.batteryPct}%")
        SpecRow("Sensors", "${d.sensors.size} detected")
        Spacer(Modifier.height(2.dp))
        Text(
            "Saved to memory — the agent will use this to decide what runs locally.",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.outline,
        )
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, fontFamily = GeistMono, fontSize = 12.sp, color = scheme.outline, modifier = Modifier.width(74.dp))
        Spacer(Modifier.size(10.dp))
        Text(value, fontSize = 13.sp, color = scheme.onSurface, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PermissionRow(title: String, why: String, permission: String) {
    val scheme = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    var isGranted by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        isGranted = granted
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(scheme.surface)
            .border(1.dp, scheme.outline.copy(alpha = 0.2f), MaterialTheme.shapes.small)
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = scheme.onSurface)
                Text(why, fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
            }
            if (isGranted) {
                Text("Granted", color = scheme.primary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            } else {
                TextButton(onClick = { launcher.launch(permission) }) {
                    Text("Allow")
                }
            }
        }
    }
}

@Composable
private fun PermissionsStep() {
    val scheme = MaterialTheme.colorScheme

    StepHeader("Permissions", "Grant what Hermes needs to help — you can change these later in system settings.")
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Spacer(Modifier.height(76.dp))
        PermissionRow("Microphone", "Voice input and commands", Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionRow("Notifications", "Proactive reminders and task updates", Manifest.permission.POST_NOTIFICATIONS)
        }
        PermissionRow("Location", "Location-aware answers and reminders", Manifest.permission.ACCESS_FINE_LOCATION)
        PermissionRow("Contacts", "Look up and message people you name", Manifest.permission.READ_CONTACTS)
        PermissionRow("Calendar", "Read and schedule events", Manifest.permission.READ_CALENDAR)
        PermissionRow("Camera", "Capture and analyze images on request", Manifest.permission.CAMERA)
        PermissionRow("Termux Commands", "Let Hermes run the full agent in Termux", "com.termux.permission.RUN_COMMAND")
        Spacer(Modifier.height(6.dp))
        Text(
            "Each is requested separately — approve the ones you're comfortable with.",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.outline,
        )
    }
}

@Composable
private fun StepHeader(title: String, subtitle: String) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Text(title, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = scheme.onBackground)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NavBar(step: Int, viewModel: OnboardingViewModel) {
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    
    Column(modifier = Modifier.fillMaxWidth()) {
        if (error != null) {
            Text(
                text = error ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > OnboardingViewModel.WELCOME) {
                TextButton(onClick = { viewModel.back() }, enabled = !saving) { Text("Back") }
            } else {
                TextButton(onClick = { viewModel.skip() }, enabled = !saving) { Text("Skip setup") }
            }
            Spacer(Modifier.weight(1f))
            if (step < OnboardingViewModel.DEVICE) {
                Button(onClick = { viewModel.next() }, shape = MaterialTheme.shapes.medium, enabled = !saving) {
                    Text(if (step == OnboardingViewModel.WELCOME) "Get started" else "Continue")
                }
            } else {
                Button(onClick = { viewModel.finish() }, shape = MaterialTheme.shapes.medium, enabled = !saving) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("Saving...")
                    } else {
                        Text(if (error != null) "Retry" else "Finish setup")
                    }
                }
            }
        }
    }
}

private fun oneDp(v: Double) = (Math.round(v * 10) / 10.0)
