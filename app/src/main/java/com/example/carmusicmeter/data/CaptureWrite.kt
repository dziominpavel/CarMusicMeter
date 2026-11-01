package com.example.carmusicmeter.data

/** Ошибка записи пачки: сколько событий потеряно и почему. */
class CaptureWriteException(
    val droppedEvents: Int,
    cause: Throwable,
) : Exception(cause)

/**
 * Тонкая обёртка над записью пачки. Вынесена из службы, чтобы ошибку записи
 * можно было проверить JVM-тестом с подменённым sink.
 */
object CaptureWrite {

    fun append(events: List<LogEvent>, sink: (List<LogEvent>) -> Unit) {
        if (events.isEmpty()) return
        try {
            sink(events)
        } catch (error: Exception) {
            throw CaptureWriteException(events.size, error)
        }
    }
}
