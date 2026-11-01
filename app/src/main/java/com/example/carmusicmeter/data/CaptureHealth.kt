package com.example.carmusicmeter.data

/** Состояние цикла наблюдения, не привязанное к Android. */
data class CaptureHealth(
    val lastTickAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null,
    val droppedEvents: Int = 0,
) {

    fun tickAttempt(wallMs: Long): CaptureHealth = copy(lastTickAt = wallMs)

    fun success(wallMs: Long): CaptureHealth = copy(
        lastSuccessAt = wallMs,
        consecutiveFailures = 0,
        lastError = null,
    )

    fun failure(message: String, dropped: Int = 0): CaptureHealth = copy(
        consecutiveFailures = consecutiveFailures + 1,
        lastError = message,
        droppedEvents = droppedEvents + dropped.coerceAtLeast(0),
    )
}

/** Задержка после ошибки: не крутим цикл в tight loop и не ждём слишком долго. */
object TickBackoff {

    const val MAX_DELAY_MS = 10_000L

    fun delayMs(failures: Int): Long = when {
        failures <= 0 -> 0L
        failures == 1 -> 1_000L
        failures == 2 -> 2_000L
        failures == 3 -> 5_000L
        else -> MAX_DELAY_MS
    }
}

/** Ограничивает запись `capture_error`, чтобы ошибка не писалась каждый тик. */
object CaptureErrorThrottle {

    const val INTERVAL_MS = 60_000L

    fun shouldLog(
        nowMs: Long,
        lastLoggedAtMs: Long?,
        failures: Int,
    ): Boolean = failures == 1 ||
        lastLoggedAtMs == null ||
        nowMs - lastLoggedAtMs >= INTERVAL_MS
}
