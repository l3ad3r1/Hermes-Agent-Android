package com.hermes.agent.receiver

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hermes.agent.MainActivity

class NotificationReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.hermes.agent.action.REPLY") {
            val remoteInput = RemoteInput.getResultsFromIntent(intent)
            val replyText = remoteInput?.getCharSequence("KEY_REPLY")?.toString()
            
            if (!replyText.isNullOrBlank()) {
                // Forward the reply to MainActivity to route to the agent.
                val replyIntent = Intent(context, MainActivity::class.java).apply {
                    action = "com.hermes.agent.action.NOTIFICATION_REPLY"
                    putExtra("EXTRA_REPLY_TEXT", replyText)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(replyIntent)
            }
        }
    }
}
