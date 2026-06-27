package com.hermes.agent.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hermes.agent.data.update.OtaUpdateChecker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class OtaUpdateWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val checker: OtaUpdateChecker,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Timber.tag("OtaWorker").d("checking for update")
        val update = runCatching { checker.check() }
            .onFailure { Timber.tag("OtaWorker").w(it, "check failed") }
            .getOrNull() ?: return Result.success()

        Timber.tag("OtaWorker").i("update available: ${update.version}")
        postNotification(update)
        return Result.success()
    }

    private fun postNotification(update: OtaUpdateChecker.UpdateInfo) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Ensure channel exists.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Hermes update availability notifications" }
            nm.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            appContext,
            0,
            Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Hermes ${update.version} available")
            .setContentText("Tap to view release and download the update.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(update.releaseNotes.ifBlank { "Tap to view release and download the update." }))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val UNIQUE_NAME = "hermes.ota_update_check"
        private const val CHANNEL_ID = "hermes_updates"
        private const val NOTIFICATION_ID = 9001
    }
}
