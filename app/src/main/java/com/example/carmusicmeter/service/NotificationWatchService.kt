package com.example.carmusicmeter.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.carmusicmeter.CarMusicMeterApp
import com.example.carmusicmeter.data.Json
import com.example.carmusicmeter.data.ListeningRepository
import com.example.carmusicmeter.data.LogEvent
import com.example.carmusicmeter.probe.NotificationCache

/**
 * Доступ к уведомлениям нужен по двум причинам:
 * 1. без него нельзя читать чужие MediaSession;
 * 2. если штатный плеер не публикует сессию, текст медиа-уведомления —
 *    резервный источник «что играет».
 */
class NotificationWatchService : NotificationListenerService() {

    private val lastSignatureByPackage = mutableMapOf<String, String>()

    private val app: CarMusicMeterApp get() = application as CarMusicMeterApp

    override fun onListenerConnected() {
        super.onListenerConnected()
        ListeningRepository.setNotificationAccess(true)
        app.eventLog.append(
            LogEvent(System.currentTimeMillis(), "notification_listener_connected"),
        )
        maybeStartWatch()
    }

    override fun onListenerDisconnected() {
        ListeningRepository.setNotificationAccess(false)
        app.eventLog.append(
            LogEvent(System.currentTimeMillis(), "notification_listener_disconnected"),
        )
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        val mediaLike = isMediaLike(notification)

        val title = text(notification, Notification.EXTRA_TITLE)
        val text = text(notification, Notification.EXTRA_TEXT)
        val bigText = text(notification, Notification.EXTRA_BIG_TEXT)
        val subText = text(notification, Notification.EXTRA_SUB_TEXT)
        val template = runCatching {
            notification.extras.getString(Notification.EXTRA_TEMPLATE)
        }.getOrNull()
        val actions = notification.actions
            ?.mapNotNull { it.title?.toString()?.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        val signature = listOf(
            sbn.packageName,
            title,
            text,
            bigText,
            subText,
            template,
            actions,
            mediaLike,
        ).joinToString("|")
        if (lastSignatureByPackage[sbn.packageName] == signature) return
        lastSignatureByPackage[sbn.packageName] = signature

        val ts = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        val fields = linkedMapOf<String, Any?>(
            "package" to sbn.packageName,
            "title" to clip(title),
            "text" to clip(text),
            "bigText" to clip(bigText),
            "subText" to clip(subText),
            "category" to notification.category,
            "template" to template,
            "ongoing" to ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0),
            "hasMediaSession" to notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION),
            "mediaLike" to mediaLike,
            "actions" to actions,
            "channelId" to notification.channelId,
            "postTime" to sbn.postTime,
        )

        NotificationCache.add(Json.objectFrom(fields, ts = ts, type = "notification"))
        if (!mediaLike) return

        val event = LogEvent(ts, "notification", fields)
        app.eventLog.append(event)
        ListeningRepository.record(listOf(event))
        maybeStartWatch()
    }

    private fun maybeStartWatch() {
        if (!app.prefs.autoStart) return
        if (ListeningRepository.state.value.serviceRunning) return
        runCatching { WatchServiceController.start(this, StartSource.NOTIFICATION) }
            .onFailure { error ->
                app.eventLog.append(
                    LogEvent(
                        System.currentTimeMillis(),
                        "service_start_failed",
                        mapOf(
                            "error" to error.message,
                            "source" to "notification_listener",
                        ),
                    ),
                )
            }
    }

    private fun isMediaLike(notification: Notification): Boolean {
        if (notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return true
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true
        val template = runCatching {
            notification.extras.getString(Notification.EXTRA_TEMPLATE)
        }.getOrNull()
        if (template?.contains("Media", ignoreCase = true) == true) return true
        val ongoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        return ongoing && !notification.actions.isNullOrEmpty()
    }

    private fun text(notification: Notification, key: String): String? =
        runCatching {
            notification.extras.getCharSequence(key)
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

    private fun clip(value: String?, limit: Int = 600): String? =
        value?.let { if (it.length <= limit) it else it.take(limit) + "…" }
}
