package com.hermes.agent.data.evolution

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.hermes.agent.data.plugin.evolution.EvolutionEvents
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts "the agent improved itself" notifications so automatic skill
 * refinements never happen invisibly. Tapping opens the app — the change is
 * reviewable under Settings → Features → Refine skills / Skills & Tools.
 *
 * Also the host side of feature evolution's [EvolutionEvents]: new proposals,
 * a module ready to review, and an override that was reverted automatically.
 */
@Singleton
class EvolutionNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : EvolutionEvents {

    private fun manager(): NotificationManager {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Skill Evolution",
                    NotificationManager.IMPORTANCE_LOW, // informative, not urgent
                ).apply { description = "Notifies when Hermes refines its skills or proposes improvements from your usage" },
            )
        }
        return nm
    }

    private fun contentIntent(): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return launchIntent?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    private fun post(id: Int, title: String, text: String, detail: String) {
        val nm = manager()
        val intent = contentIntent()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .apply { intent?.let { setContentIntent(it) } }
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, notification) }
    }

    override fun proposalsReady(count: Int) {
        if (count <= 0) return
        post(
            PROPOSALS_ID,
            if (count == 1) "1 improvement proposed" else "$count improvements proposed",
            "Found from how you use Hermes. Tap to review.",
            "Hermes analysed your recent usage and proposed $count improvement(s). Nothing changes until you " +
                "approve it under Settings → Feature evolution.",
        )
    }

    override fun moduleReady(title: String) {
        post(
            READY_ID,
            "Ready to review: $title",
            "Built and reviewed by your bots. Tap to review and install.",
            "Your builder and reviewer bots finished \"$title\". Review the code, permissions and test results under " +
                "Settings → Feature evolution before installing.",
        )
    }

    override fun overrideReverted(toolName: String, moduleId: String, reason: String) {
        post(
            REVERTED_ID,
            "Reverted a fix for $toolName",
            "It kept failing, so Hermes went back to the built-in tool.",
            "The evolution module $moduleId replaced $toolName but failed repeatedly ($reason). It was switched off " +
                "and the built-in tool is back. See Settings → Feature evolution.",
        )
    }

    fun notifySkillsImproved(skillNames: List<String>) {
        if (skillNames.isEmpty()) return
        val nm = manager()
        val contentIntent = contentIntent()

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
                    "Hermes refined ${skillNames.joinToString(", ")} based on your recent " +
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
        private const val PROPOSALS_ID = 9003
        private const val READY_ID = 9004
        private const val REVERTED_ID = 9005
    }
}
