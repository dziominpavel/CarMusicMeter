package com.example.carmusicmeter.data

/**
 * Пассивно перехваченное нажатие media button.
 *
 * Android-зависимостей нет: сервис передаёт сюда примитивы из `KeyEvent`,
 * поэтому модель проверяется обычными JVM-тестами.
 */
data class MediaButtonEvent(
    val keyCode: Int,
    val action: String,
    val eventTime: Long,
    val repeatCount: Int,
    val sourcePackage: String? = null,
) {

    val keyName: String
        get() = keyName(keyCode)

    fun toLogEvent(nowMs: Long, live: LiveTrack?): LogEvent = LogEvent(
        ts = nowMs,
        type = "media_button",
        fields = linkedMapOf(
            "keyCode" to keyCode,
            "key" to keyName,
            "action" to action,
            "eventTime" to eventTime,
            "repeatCount" to repeatCount,
            "sourcePackage" to sourcePackage,
            "trackKey" to live?.key,
            "title" to live?.title,
            "artist" to live?.artist,
        ),
    )

    companion object {

        /** Значения совпадают с `KeyEvent.ACTION_DOWN`/`ACTION_UP`. */
        fun fromKey(
            keyCode: Int,
            action: Int,
            eventTime: Long,
            repeatCount: Int,
            sourcePackage: String? = null,
        ): MediaButtonEvent? {
            val actionName = when (action) {
                0 -> "down"
                1 -> "up"
                else -> return null
            }
            return MediaButtonEvent(
                keyCode = keyCode,
                action = actionName,
                eventTime = eventTime,
                repeatCount = repeatCount,
                sourcePackage = sourcePackage,
            )
        }

        fun keyName(keyCode: Int): String = when (keyCode) {
            85 -> "media_play_pause"
            86 -> "media_stop"
            87 -> "media_next"
            88 -> "media_previous"
            126 -> "media_play"
            127 -> "media_pause"
            else -> "key_$keyCode"
        }
    }
}
