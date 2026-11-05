package com.example.carmusicmeter.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Насколько настенные часы могут отстать от последнего достоверного времени,
 * прежде чем считаться сбитыми. Минуты — обычная погрешность, час — уже сбой.
 */
const val MAX_CLOCK_BACKWARD_MS = 60L * 60L * 1000L

private val buildDateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

/**
 * Можно ли верить настенным часам магнитолы.
 *
 * Teyes CC3 после полной потери питания стартует с фиксированной датой
 * (наблюдалось `2023-05-10`) и получает реальное время по NTP только через
 * десятки секунд. Служба автозапуска стартует раньше, поэтому без этой
 * проверки первые события уходят в файл 2023 года и выгрузка начинает
 * «начинаться» в 2023 году.
 *
 * Часы недостоверны, если время раньше даты сборки приложения (так не бывает)
 * или заметно откатилось относительно последнего достоверного времени.
 */
fun isClockPlausible(
    nowMs: Long,
    buildTimeMs: Long?,
    lastVerifiedWallMs: Long?,
): Boolean {
    if (buildTimeMs != null && buildTimeMs > 0L && nowMs < buildTimeMs) return false
    if (lastVerifiedWallMs != null && lastVerifiedWallMs > 0L &&
        nowMs < lastVerifiedWallMs - MAX_CLOCK_BACKWARD_MS
    ) {
        return false
    }
    return true
}

/**
 * Разбор `BuildConfig.BUILD_DATE` (`dd.MM.yyyy HH:mm`) в epoch ms.
 *
 * Возвращает `null`, если строки нет или формат другой: проверка часов тогда
 * опирается только на последнее достоверное время.
 */
fun parseBuildDate(
    value: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): Long? {
    if (value.isNullOrBlank()) return null
    return try {
        LocalDateTime.parse(value.trim(), buildDateFormat)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}
