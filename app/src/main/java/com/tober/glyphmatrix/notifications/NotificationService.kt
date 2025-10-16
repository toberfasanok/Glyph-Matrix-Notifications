package com.tober.glyphmatrix.notifications

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationService : NotificationListenerService() {
    private val tag = "Notification Listener"

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
        super.onNotificationPosted(statusBarNotification)

        Log.d(tag, "Notification posted from: ${statusBarNotification.packageName}")

        val notification = statusBarNotification.notification
        val extras = notification.extras

        val intent = Intent(this, GlyphMatrixService::class.java).apply {
            action = Constants.ACTION_ON_GLYPH
            putExtra(Constants.NOTIFICATION_EXTRA_PKG, statusBarNotification.packageName)
            putExtra(Constants.NOTIFICATION_EXTRA_TITLE, extras.getString(android.app.Notification.EXTRA_TITLE))
            putExtra(Constants.NOTIFICATION_EXTRA_TEXT, extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString())
        }

        startService(intent)
    }

    override fun onNotificationRemoved(statusBarNotification: StatusBarNotification) {
        super.onNotificationRemoved(statusBarNotification)

        Log.d(tag, "Notification removed from: ${statusBarNotification.packageName}")
    }
}
