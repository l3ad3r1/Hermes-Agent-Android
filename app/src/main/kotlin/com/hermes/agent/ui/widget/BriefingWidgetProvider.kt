package com.hermes.agent.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.hermes.agent.MainActivity
import com.hermes.agent.R

class BriefingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_briefing)
        
        // Tap to chat intent
        val chatIntent = Intent(context, MainActivity::class.java).apply {
            action = "com.hermes.agent.action.ASK_JEEVES"
        }
        val chatPendingIntent = PendingIntent.getActivity(
            context, 0, chatIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_briefing_chat_button, chatPendingIntent)

        // Play briefing intent
        val playIntent = Intent(context, MainActivity::class.java).apply {
            action = "com.hermes.agent.action.PLAY_BRIEFING"
        }
        val playPendingIntent = PendingIntent.getActivity(
            context, 1, playIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_briefing_play_button, playPendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
