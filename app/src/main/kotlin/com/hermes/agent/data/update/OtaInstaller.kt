package com.hermes.agent.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.hermes.agent.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads a release APK in-app (no browser) and hands it to the system
 * package installer.
 *
 * Flow: stream the .apk into app-specific external storage → expose it through
 * [FileProvider] → fire ACTION_VIEW with the package-archive MIME so Android's
 * installer takes over. On Android 8+ the caller needs the "install unknown
 * apps" permission ([canInstallPackages]); [promptInstallPermission] routes the
 * user to the right settings screen.
 */
@Singleton
class OtaInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {

    /** True when the app is already allowed to install packages (always true pre-O). */
    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Opens the system "install unknown apps" screen for this app. */
    fun promptInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Timber.tag("OtaInstaller").w(it, "cannot open unknown-sources settings") }
    }

    /**
     * Starts the foreground service to download [apkUrl] and launch the installer.
     */
    fun startDownloadService(apkUrl: String) {
        val intent = Intent(context, OtaDownloadService::class.java).apply {
            action = OtaDownloadService.ACTION_START_DOWNLOAD
            putExtra(OtaDownloadService.EXTRA_URL, apkUrl)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
