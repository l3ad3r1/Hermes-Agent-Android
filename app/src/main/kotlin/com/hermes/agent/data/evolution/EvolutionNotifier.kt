package com.hermes.agent.data.evolution

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts "the agent improved itself" notifications so automatic skill
 * refinements never happen invisibly. Tapping opens the app — the change is
 * reviewable under Settings → Features → Refine skills / Skills & Tools.
 */
@Singleton
class EvolutionNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun notifySkillsImproved(skillNames: List<String>) {
        if (skillNames.isEmpty()) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Skill Evolution",
                    NotificationManager.IMPORTANCE_LOW, // informative, not urgent
                ).apply { description = "Notifies when Jeeves refines its skills from your usage" },
            )
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val title = if (skillNames.size == 1) {
            "Skill improved: ${skillNames.first()}"
        } else {
            "${skillNames.size} skills improved"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(title)
            .setContentText("Refined from how you actually used them. Tap to review.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Jeeves refined ${skillNames.joinToString(", ")} based on your recent " +
                        "usage. Review under Settings → Skills & Tools.",
                ),
            )
            .apply { contentIntent?.let { setContentIntent(it) } }
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "hermes_evolution"
        private const val NOTIFICATION_ID = 9002
    }
}
