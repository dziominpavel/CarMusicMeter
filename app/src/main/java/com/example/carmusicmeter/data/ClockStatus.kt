package com.example.carmusicmeter.data

/**
 * Состояние настенных часов магнитолы в текущем процессе.
 *
 * Инициализируется один раз в `Application.onCreate`, до первого события:
 * от него зависит, в какой файл журнала попадёт первая же строка. Пока часы
 * недостоверны, события пишутся в `events-unverified.jsonl` и помечаются
 * `clockVerified = false`; после первого исправления — в файл по дате.
 */
object ClockStatus {

    /** Часам можно верить; `false` — журнал пишется в непроверенный поток. */
    @Volatile
    var verified: Boolean = true
        private set

    /** Последнее достоверное настенное время, мс. Отдаётся и в настройки. */
    @Volatile
    var lastVerifiedWallMs: Long = 0L
        private set

    /** Когда часы исправились в этом процессе; `null` — не исправлялись. */
    @Volatile
    var verifiedAtMs: Long? = null
        private set

    /** Поправка скачка: прибавить к `ts` событий, записанных до него. */
    @Volatile
    var shiftMs: Long = 0L
        private set

    fun init(nowMs: Long, buildTimeMs: Long?, storedLastVerifiedWallMs: Long?) {
        verified = isClockPlausible(nowMs, buildTimeMs, storedLastVerifiedWallMs)
        lastVerifiedWallMs = if (verified) nowMs else (storedLastVerifiedWallMs ?: 0L)
        verifiedAtMs = null
        shiftMs = 0L
    }

    /** Очередной тик с достоверным временем. */
    fun touchVerified(nowMs: Long) {
        verified = true
        lastVerifiedWallMs = nowMs
    }

    /** Часы исправились: скачок с поправкой `driftMs`. */
    fun onClockChanged(nowMs: Long, driftMs: Long) {
        verified = true
        verifiedAtMs = nowMs
        lastVerifiedWallMs = nowMs
        shiftMs = driftMs
    }

    /** Сброс между тестами: глобальное состояние иначе переживает один тест. */
    fun reset() {
        verified = true
        lastVerifiedWallMs = 0L
        verifiedAtMs = null
        shiftMs = 0L
    }

    /**
     * Блок `clock` для снимка диагностики.
     *
     * `unverifiedEvents` живёт в `EventLog` (там же, где счётчик записи),
     * поэтому передаётся снаружи.
     */
    fun toJson(unverifiedEvents: Long): Map<String, Any?> = linkedMapOf(
        "verified" to verified,
        "lastVerifiedAt" to lastVerifiedWallMs.takeIf { it > 0L },
        "verifiedAt" to verifiedAtMs,
        "shiftMs" to shiftMs,
        "unverifiedEvents" to unverifiedEvents,
    )
}
