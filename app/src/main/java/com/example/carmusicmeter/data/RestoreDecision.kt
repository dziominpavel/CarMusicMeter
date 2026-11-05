package com.example.carmusicmeter.data

/**
 * Сколько ждать метаданные, прежде чем закрыть восстановленное прослушивание.
 *
 * После холодного старта штатный плеер может отдать состояние не сразу.
 */
const val RESTORE_GRACE_MS = 10_000L

/** Что делать с прослушиванием, оставшимся от предыдущего процесса. */
enum class RestoreDecision {
    /** Играет тот же трек — продолжаем прослушивание. */
    CONTINUE,

    /** Трек другой или метаданные так и не пришли — закрываем. */
    CLOSE,

    /** Снимка ещё нет, но ждать ещё можно. */
    WAIT,
}

/**
 * Решение по восстановленному прослушиванию.
 *
 * Вынесено из службы, чтобы поведение проверялось JVM-тестами: закрытие на
 * `playedMs = 0` при замене APK давало лишнее прослушивание того же трека.
 *
 * @param pendingTrackKey ключ трека из сохранённого прослушивания
 * @param snapshotTrackKey ключ текущего снимка; `null` — источника пока нет
 * @param elapsedMs сколько прошло с загрузки сохранённого состояния
 */
fun decideRestore(
    pendingTrackKey: String,
    snapshotTrackKey: String?,
    elapsedMs: Long,
    graceMs: Long = RESTORE_GRACE_MS,
): RestoreDecision = when {
    snapshotTrackKey == null ->
        if (elapsedMs <= graceMs) RestoreDecision.WAIT else RestoreDecision.CLOSE
    snapshotTrackKey == pendingTrackKey -> RestoreDecision.CONTINUE
    else -> RestoreDecision.CLOSE
}
