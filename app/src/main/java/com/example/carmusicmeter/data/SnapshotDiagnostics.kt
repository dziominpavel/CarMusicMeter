package com.example.carmusicmeter.data

/** Чистые поля снимка, которые можно проверить без Android-контекста. */
object SnapshotDiagnostics {

    fun notificationFlags(
        accessGranted: Boolean,
        listenerConnected: Boolean,
    ): Map<String, Boolean> = linkedMapOf(
        "notificationAccess" to accessGranted,
        "notificationListenerConnected" to listenerConnected,
    )
}
