package com.example.carmusicmeter.data

/** Единое состояние сбора, которое видит главный экран. */
enum class CaptureStatus {
    RECORDING,
    DEGRADED,
    NOT_RECORDING,
    ERROR,
}

/** Действие, которое главный экран предлагает рядом со статусом. */
enum class CaptureAction {
    START_SERVICE,
    OPEN_NOTIFICATION_SETTINGS,
    OPEN_DIAGNOSTICS,
    NONE,
}

/**
 * Производное представление статуса. Не хранится в LiveState: зависит от
 * текущего времени и должно пересчитываться на каждом тике экрана.
 */
data class CaptureStatusView(
    val status: CaptureStatus,
    val headline: String,
    val detail: String?,
    val lastSuccessAt: Long?,
    val action: CaptureAction,
)

/** После этого порога успешный тик считается устаревшим. */
const val CAPTURE_STALE_AFTER_MS: Long = 10_000L

/**
 * Считает один статус вместо нескольких независимых индикаторов.
 * Приоритет важен: остановленная служба важнее старой ошибки, ошибка —
 * важнее устаревшего тика, а потеря данных важнее «всё зелёное».
 */
fun deriveCaptureStatus(state: LiveState, nowMs: Long): CaptureStatusView {
    if (!state.serviceRunning) {
        return CaptureStatusView(
            status = CaptureStatus.NOT_RECORDING,
            headline = "Сбор остановлен",
            detail = "Служба наблюдения выключена",
            lastSuccessAt = state.lastSuccessAt,
            action = CaptureAction.START_SERVICE,
        )
    }

    state.captureError?.let { error ->
        return CaptureStatusView(
            status = CaptureStatus.ERROR,
            headline = "Ошибка сбора",
            detail = error,
            lastSuccessAt = state.lastSuccessAt,
            action = CaptureAction.OPEN_DIAGNOSTICS,
        )
    }

    val lastSuccess = state.lastSuccessAt
    if (lastSuccess == null) {
        return CaptureStatusView(
            status = CaptureStatus.NOT_RECORDING,
            headline = "Сбор запускается",
            detail = "Успешных тиков ещё не было",
            lastSuccessAt = null,
            action = CaptureAction.OPEN_DIAGNOSTICS,
        )
    }

    if (nowMs - lastSuccess > CAPTURE_STALE_AFTER_MS) {
        return CaptureStatusView(
            status = CaptureStatus.NOT_RECORDING,
            headline = "Нет свежих данных",
            detail = "Данные не обновлялись",
            lastSuccessAt = lastSuccess,
            action = CaptureAction.OPEN_DIAGNOSTICS,
        )
    }

    if (state.droppedEvents > 0) {
        return CaptureStatusView(
            status = CaptureStatus.DEGRADED,
            headline = "Сбор идёт с замечаниями",
            detail = "Потеряно событий: ${state.droppedEvents}",
            lastSuccessAt = lastSuccess,
            action = CaptureAction.OPEN_DIAGNOSTICS,
        )
    }

    val noTrack = state.live == null && state.stockSource == null
    if (state.musicActive && noTrack && !state.notificationAccess) {
        return CaptureStatusView(
            status = CaptureStatus.DEGRADED,
            headline = "Нет доступа к уведомлениям",
            detail = "Без него приложение не читает MediaSession",
            lastSuccessAt = lastSuccess,
            action = CaptureAction.OPEN_NOTIFICATION_SETTINGS,
        )
    }

    if (state.musicActive && noTrack) {
        return CaptureStatusView(
            status = CaptureStatus.DEGRADED,
            headline = "Музыка играет, источника нет",
            detail = "Проверьте чтение интерфейса или откройте диагностику",
            lastSuccessAt = lastSuccess,
            action = CaptureAction.OPEN_DIAGNOSTICS,
        )
    }

    return CaptureStatusView(
        status = CaptureStatus.RECORDING,
        headline = "Сбор идёт",
        detail = null,
        lastSuccessAt = lastSuccess,
        action = CaptureAction.NONE,
    )
}
