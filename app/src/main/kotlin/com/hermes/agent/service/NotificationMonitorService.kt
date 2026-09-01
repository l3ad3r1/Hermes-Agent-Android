package com.hermes.agent.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hermes.agent.data.notifications.CapturedNotification
import com.hermes.agent.data.notifications.NotificationGateway
import timber.log.Timber

/**
 * Android NotificationListenerService to observe active system notifications
 * and feed them into NotificationGateway for ReadNotificationsTool.
 */
class NotificationMonitorService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Timber.tag("NotificationMonitor").i("NotificationListenerService connected")
        refreshActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        val captured = toCapturedNotification(sbn)
        NotificationGateway.onNotificationPosted(captured)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        NotificationGateway.onNotificationRemoved(sbn.key)
    }

    private fun refreshActiveNotifications() {
        try {
            val sbns = activeNotifications ?: return
            val capturedList = sbns.map { toCapturedNotification(it) }
            NotificationGateway.updateActiveNotifications(capturedList)
        } catch (e: Exception) {
            Timber.tag("NotificationMonitor").w(e, "Could not refresh active notifications")
        }
    }

    private fun toCapturedNotification(sbn: StatusBarNotification): CapturedNotification {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""

        return CapturedNotification(
            id = sbn.id,
            packageName = sbn.packageName ?: "",
            title = title,
            text = text,
            postTime = sbn.postTime,
            key = sbn.key,
            isClearable = sbn.isClearable,
        )
    }
}
