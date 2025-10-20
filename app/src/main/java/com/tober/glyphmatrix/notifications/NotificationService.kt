package com.tober.glyphmatrix.notifications

import android.app.Notification
import android.app.Person
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

class NotificationService : NotificationListenerService() {
    private val tag = "Notification Listener"

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
        super.onNotificationPosted(statusBarNotification)

        Log.d(tag, "Notification posted from: ${statusBarNotification.packageName}")

        val notification = statusBarNotification.notification
        val extras = notification.extras

        val contact: String? = run {
            try {
                val people: ArrayList<Person>? =
                    extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, Person::class.java)
                if (!people.isNullOrEmpty()) {
                    val p = people[0]
                    p.name?.let { if (it.isNotBlank()) return@run it.toString() }
                    return@run p.toString()
                }
            } catch (e: Exception) {
                Log.w(tag, "People extraction failed: $e")
            }

            try {
                val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)

                if (messagingStyle != null) {
                    messagingStyle.conversationTitle?.toString()?.takeIf { it.isNotBlank() }?.let { return@run it }

                    val messages = messagingStyle.messages

                    if (!messages.isNullOrEmpty()) {
                        val last = messages.last()

                        try {
                            val getSender = last.javaClass.getMethod("getSender")
                            val sender = getSender.invoke(last) as? CharSequence
                            if (!sender.isNullOrBlank()) return@run sender.toString()
                        } catch (_: Exception) {}

                        try {
                            val getBundle = last.javaClass.getMethod("getData")
                            val data = getBundle.invoke(last)
                            if (data is Bundle) {
                                val sender = data.getString("sender") ?: data.getCharSequence("sender")?.toString()
                                if (!sender.isNullOrBlank()) return@run sender
                            }
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "MessagingStyle extraction failed: $e")
            }

            try {
                val parcels = extras.getParcelableArray("android.messages", Bundle::class.java)

                if (parcels != null) {
                    for (p in parcels) {
                        if (p is Bundle) {
                            val sender = p.getString("sender") ?: p.getCharSequence("sender")?.toString()
                            if (!sender.isNullOrBlank()) return@run sender
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Android messages extraction failed: $e")
            }

            try {
                val title = extras.getCharSequence("android.conversationTitle")?.toString()
                    ?: extras.getCharSequence("android.title")?.toString()
                if (!title.isNullOrBlank()) return@run title
            } catch (_: Exception) {}

            try {
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                if (!title.isNullOrBlank()) return@run title
            } catch (_: Exception) {}

            ""
        }

        Log.d(tag, "Resolved contact: $contact")

        val intent = Intent(this, GlyphMatrixService::class.java).apply {
            action = Constants.ACTION_ON_NOTIFICATION
            putExtra(Constants.NOTIFICATION_EXTRA_PKG, statusBarNotification.packageName)
            putExtra(Constants.NOTIFICATION_EXTRA_CONTACT, contact)
        }

        startService(intent)
    }

    override fun onNotificationRemoved(statusBarNotification: StatusBarNotification) {
        super.onNotificationRemoved(statusBarNotification)

        Log.d(tag, "Notification removed from: ${statusBarNotification.packageName}")
    }
}
