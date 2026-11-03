package com.example.carmusicmeter.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Состояние, которое видит экран диагностики. */
data class LiveState(
    val serviceRunning: Boolean = false,
    val notificationAccess: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val musicActive: Boolean = false,
    val fytMetadataReceived: Boolean = false,
    val fytMetadataAt: Long? = null,
    val batteryUnrestricted: Boolean = false,
    val live: LiveTrack? = null,
    val volume: Int = 0,
    val volumeMax: Int = 0,
    val volumeSource: String = "audio",
    val teyesAvailable: Boolean = false,
    val teyesError: String? = null,
    val mcuVolume: Int? = null,
    val mcuMuted: Boolean? = null,
    val mcuMax: Int? = null,
    val stockSource: String? = null,
    val error: String? = null,
    val lastEventAt: Long? = null,
    val eventsToday: Int = 0,
    val tracksToday: Int = 0,
    val sessionsSeen: Int = 0,
    val recent: List<LogEvent> = emptyList(),
    val lastTickAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val captureError: String? = null,
    val captureFailures: Int = 0,
    val droppedEvents: Int = 0,
    val teyesDevice: Boolean = false,
    val notificationListenerConnected: Boolean = false,
) {
    /** Показывать ли Teyes-специфичные источники и настройки. */
    val teyesRelevant: Boolean
        get() = teyesDevice || stockSource != null || fytMetadataReceived || teyesAvailable
}

/**
 * Единственная точка обмена состоянием между службой и UI.
 * Живёт в процессе; после перезапуска счётчики восстанавливаются из файла.
 */
object ListeningRepository {

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    fun attach(log: EventLog) {
        val (events, tracks) = log.todayCounts()
        _state.update { it.copy(eventsToday = events, tracksToday = tracks) }
    }

    fun setServiceRunning(running: Boolean) {
        _state.update { it.copy(serviceRunning = running) }
    }

    fun setNotificationAccess(granted: Boolean) {
        _state.update { it.copy(notificationAccess = granted) }
    }

    fun setNotificationListenerConnected(connected: Boolean) {
        _state.update { it.copy(notificationListenerConnected = connected) }
    }

    fun setTeyesDevice(teyes: Boolean) {
        _state.update { it.copy(teyesDevice = teyes) }
    }

    fun setAccessibilityEnabled(enabled: Boolean) {
        _state.update { it.copy(accessibilityEnabled = enabled) }
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _state.update { it.copy(accessibilityConnected = connected) }
    }

    fun setBatteryUnrestricted(unrestricted: Boolean) {
        _state.update { it.copy(batteryUnrestricted = unrestricted) }
    }

    fun updateCaptureHealth(health: CaptureHealth) {
        _state.update {
            it.copy(
                lastTickAt = health.lastTickAt,
                lastSuccessAt = health.lastSuccessAt,
                captureError = health.lastError,
                captureFailures = health.consecutiveFailures,
                droppedEvents = health.droppedEvents,
            )
        }
    }

    fun updateLive(
        live: LiveTrack?,
        volume: Int,
        volumeMax: Int,
        notificationAccess: Boolean,
        error: String?,
        volumeSource: String = "audio",
        teyesAvailable: Boolean = false,
        teyesError: String? = null,
        mcuVolume: Int? = null,
        mcuMuted: Boolean? = null,
        mcuMax: Int? = null,
        stockSource: String? = null,
        accessibilityEnabled: Boolean = false,
        accessibilityConnected: Boolean = false,
        musicActive: Boolean = false,
        fytMetadataReceived: Boolean = false,
        fytMetadataAt: Long? = null,
    ) {
        _state.update { previous ->
            val newSession = live != null && live.packageName != previous.live?.packageName
            previous.copy(
                live = live,
                volume = volume,
                volumeMax = volumeMax,
                volumeSource = volumeSource,
                teyesAvailable = teyesAvailable,
                teyesError = teyesError,
                mcuVolume = mcuVolume,
                mcuMuted = mcuMuted,
                mcuMax = mcuMax,
                stockSource = stockSource,
                accessibilityEnabled = accessibilityEnabled,
                accessibilityConnected = accessibilityConnected,
                musicActive = musicActive,
                fytMetadataReceived = fytMetadataReceived,
                fytMetadataAt = fytMetadataAt,
                notificationAccess = notificationAccess,
                error = error,
                sessionsSeen = if (newSession) previous.sessionsSeen + 1 else previous.sessionsSeen,
            )
        }
    }

    fun record(events: List<LogEvent>) {
        if (events.isEmpty()) return
        _state.update { previous ->
            previous.copy(
                eventsToday = previous.eventsToday + events.size,
                tracksToday = previous.tracksToday + events.count { it.type == "track_started" },
                lastEventAt = events.last().ts,
                recent = (events.reversed() + previous.recent).take(50),
            )
        }
    }
}
