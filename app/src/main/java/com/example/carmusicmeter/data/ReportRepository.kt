package com.example.carmusicmeter.data

import com.example.carmusicmeter.data.metrics.MetricsEngine
import com.example.carmusicmeter.data.metrics.ReportModel
import com.example.carmusicmeter.data.metrics.ReportPeriod
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ReportUiState {
    data class Loading(val done: Int = 0, val total: Int = 0) : ReportUiState
    data class Ready(val model: ReportModel) : ReportUiState
    data object Empty : ReportUiState
    data class Error(val message: String) : ReportUiState
}

/**
 * Фоновый расчёт отчёта: читает факты, считает метрики и отдаёт состояние
 * экрану. Живёт вне Compose, поэтому переживает перерисовку и отменяется
 * вместе с Activity.
 */
class ReportRepository(
    private val baseDir: File,
    private val deviceId: String,
    private val appVersion: String,
    private val liveSince: () -> Long?,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<ReportUiState>(ReportUiState.Loading())
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var lastPeriod: ReportPeriod = ReportPeriod.DAYS_7

    fun load(period: ReportPeriod, nowMs: Long = System.currentTimeMillis()) {
        job?.cancel()
        lastPeriod = period
        val fromMs = period.fromMs(nowMs)
        _state.value = ReportUiState.Loading(0, 0)
        job = scope.launch {
            try {
                val source = PlaySource(
                    baseDir = baseDir,
                    deviceId = deviceId,
                    appVersion = appVersion,
                    liveSince = liveSince(),
                    nowMs = nowMs,
                )
                val plays = withContext(Dispatchers.IO) {
                    source.load(fromMs, null) { done, total ->
                        _state.value = ReportUiState.Loading(done, total)
                    }
                }
                val gaps = withContext(Dispatchers.IO) {
                    source.captureGaps(fromMs, null)
                }
                val model = withContext(Dispatchers.Default) {
                    MetricsEngine.compute(plays, period, gaps, nowMs)
                }
                _state.value = if (
                    model.summary.plays == 0 &&
                    model.openPlay == null &&
                    model.unverifiedCount == 0
                ) {
                    ReportUiState.Empty
                } else {
                    ReportUiState.Ready(model)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.value = ReportUiState.Error(
                    error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    fun retry(nowMs: Long = System.currentTimeMillis()) {
        load(lastPeriod, nowMs)
    }

    fun cancel() {
        job?.cancel()
    }
}
