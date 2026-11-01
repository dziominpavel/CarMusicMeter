package com.example.carmusicmeter.data

/**
 * Фактическое состояние подключения NotificationListenerService.
 * Отдельно от разрешения: разрешение может быть выдано, а listener — нет.
 */
object NotificationListenerStatus {

    @Volatile
    var connected: Boolean = false
}
