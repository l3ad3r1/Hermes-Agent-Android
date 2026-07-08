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
     * Downloads [apkUrl] (reporting 0..100 progress) and launches the installer.
     * Returns failure if the download fails; a successful result means the
     * system installer was launched.
     */
    suspend fun downloadAndInstall(
        apkUrl: String,
        onProgress: (Int) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val file = download(apkUrl, onProgress)
            launchInstaller(file)
        }.onFailure { Timber.tag("OtaInstaller").w(it, "download/install failed") }
    }

    private fun download(url: String, onProgress: (Int) -> Unit): File {
        if (url.isBlank()) throw IOException("No APK asset attached to this release.")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Hermes-Agent-Android/${BuildConfig.VERSION_NAME}")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code}).")
            val body = response.body ?: throw IOException("Empty download response.")
            val total = body.contentLength()

            val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
            val file = File(dir, "hermes-update.apk")
            // Clear any stale partial download.
            if (file.exists()) file.delete()

            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            onProgress(100)
            return file
        }
    }

    private fun launchInstaller(file: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
