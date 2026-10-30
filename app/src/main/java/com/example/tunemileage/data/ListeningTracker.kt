package com.example.tunemileage.data

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Превращает последовательность снимков медиасессии в события журнала.
 *
 * Трекер намеренно не знает про Android: время, снимки и громкость приходят
 * снаружи, поэтому логику пропусков, пауз и перемоток можно проверить
 * обычными JVM-тестами.
 */
class ListeningTracker {

    private data class Active(
        val key: String,
        var snapshot: MediaSnapshot,
        val startedAtMs: Long,
        var playedMs: Long,
        var running: Boolean,
        var lastTickAtMs: Long,
        var lastSeenAtMs: Long,
        var lastPositionMs: Long?,
        var lastPositionAtMs: Long,
        var volumeStart: Int,
        var volumeLow: Int,
        var volumeHigh: Int,
        var volumeChanges: Int,
    )

    data class TickResult(
        val events: List<LogEvent>,
        val live: LiveTrack?,
    )

    private var active: Active? = null
    private var lastVolume: Int? = null
    private var lastVolumeMax: Int? = null

    val currentPackage: String?
        get() = active?.snapshot?.packageName

    /**
     * Отдельный быстрый опрос громкости. Не создаёт событий, пока значение
     * не изменилось; при изменении привязывает его к текущему треку.
     */
    fun onVolume(nowMs: Long, volume: Int, volumeMax: Int): List<LogEvent> {
        val previous = lastVolume
        val previousMax = lastVolumeMax
        lastVolume = volume
        lastVolumeMax = volumeMax
        if (previous == null || previousMax == null) return emptyList()
        if (previous == volume && previousMax == volumeMax) return emptyList()

        val current = active
        current?.let {
            it.volumeChanges += 1
            it.volumeLow = minOf(it.volumeLow, volume)
            it.volumeHigh = maxOf(it.volumeHigh, volume)
        }

        return listOf(
            LogEvent(
                ts = nowMs,
                type = "volume_changed",
                fields = linkedMapOf(
                    "previous" to previous,
                    "value" to volume,
                    "max" to volumeMax,
                    "previousMax" to previousMax,
                    "delta" to (volume - previous),
                    "level" to if (volumeMax > 0) volume.toDouble() / volumeMax else 0.0,
                    "trackKey" to current?.key,
                    "title" to current?.snapshot?.title,
                    "artist" to current?.snapshot?.artist,
                    "trackPositionMs" to current?.let { extrapolatePosition(it, nowMs) },
                ),
            ),
        )
    }

    fun onTick(
        nowMs: Long,
        snapshot: MediaSnapshot?,
        volume: Int,
        volumeMax: Int,
    ): TickResult {
        if (lastVolume == null) {
            lastVolume = volume
            lastVolumeMax = volumeMax
        }

        val events = mutableListOf<LogEvent>()
        var current = active

        if (current == null) {
            if (snapshot != null) {
                current = start(nowMs, snapshot, volume)
                active = current
                events += startEvent(current, nowMs)
            }
            return TickResult(events, liveTrack(nowMs, volume, volumeMax))
        }

        accumulate(current, nowMs)

        if (snapshot == null) {
            if (nowMs - current.lastSeenAtMs > SESSION_GRACE_MS) {
                events += endEvent(current, nowMs, next = null, explicitReason = "session_lost")
                active = null
            }
            return TickResult(events, liveTrack(nowMs, volume, volumeMax))
        }

        current.lastSeenAtMs = nowMs

        if (snapshot.trackKey != current.key) {
            events += endEvent(current, nowMs, next = snapshot, explicitReason = null)
            val next = start(nowMs, snapshot, volume)
            active = next
            events += startEvent(next, nowMs)
            return TickResult(events, liveTrack(nowMs, volume, volumeMax))
        }

        val oldPhase = current.snapshot.phase
        val newPhase = snapshot.phase
        val phaseChanged = oldPhase != newPhase

        if (phaseChanged) {
            val eventType = when (newPhase) {
                MediaPhase.PLAYING -> "playback_resumed"
                MediaPhase.PAUSED -> "playback_paused"
                MediaPhase.STOPPED -> "playback_stopped"
                MediaPhase.UNKNOWN -> null
            }
            if (eventType != null) {
                events += LogEvent(
                    ts = nowMs,
                    type = eventType,
                    fields = linkedMapOf(
                        "trackKey" to current.key,
                        "package" to current.snapshot.packageName,
                        "title" to current.snapshot.title,
                        "artist" to current.snapshot.artist,
                        "positionMs" to (snapshot.positionMs ?: extrapolatePosition(current, nowMs)),
                        "playedMs" to current.playedMs,
                    ),
                )
            }
        }

        if (!phaseChanged) {
            val reported = snapshot.positionMs
            val previous = current.lastPositionMs
            if (reported != null && previous != null && reported != previous) {
                val expected = expectedPosition(current, nowMs)
                val delta = reported - expected
                if (abs(delta) > SEEK_THRESHOLD_MS) {
                    events += LogEvent(
                        ts = nowMs,
                        type = "seek",
                        fields = linkedMapOf(
                            "trackKey" to current.key,
                            "title" to current.snapshot.title,
                            "artist" to current.snapshot.artist,
                            "fromMs" to expected,
                            "toMs" to reported,
                            "deltaMs" to delta,
                        ),
                    )
                }
                current.lastPositionMs = reported
                current.lastPositionAtMs = nowMs
            } else if (reported != null && previous == null) {
                current.lastPositionMs = reported
                current.lastPositionAtMs = nowMs
            }
        } else {
            // При смене фазы позиция может «отставать» от нашей экстраполяции:
            // это не перемотка, а пауза/возобновление. Просто берём новую базу.
            snapshot.positionMs?.let {
                current.lastPositionMs = it
                current.lastPositionAtMs = nowMs
            }
        }

        current.running = newPhase == MediaPhase.PLAYING
        current.snapshot = snapshot

        return TickResult(events, liveTrack(nowMs, volume, volumeMax))
    }

    /** Закрыть текущий трек, например при остановке службы. */
    fun finish(
        nowMs: Long = System.currentTimeMillis(),
        reason: String = "service_stopped",
    ): List<LogEvent> {
        val current = active ?: return emptyList()
        accumulate(current, nowMs)
        val event = endEvent(current, nowMs, next = null, explicitReason = reason)
        active = null
        return listOf(event)
    }

    private fun start(nowMs: Long, snapshot: MediaSnapshot, volume: Int): Active = Active(
        key = snapshot.trackKey,
        snapshot = snapshot,
        startedAtMs = nowMs,
        playedMs = 0L,
        running = snapshot.phase == MediaPhase.PLAYING,
        lastTickAtMs = nowMs,
        lastSeenAtMs = nowMs,
        lastPositionMs = snapshot.positionMs,
        lastPositionAtMs = nowMs,
        volumeStart = volume,
        volumeLow = volume,
        volumeHigh = volume,
        volumeChanges = 0,
    )

    private fun accumulate(active: Active, nowMs: Long) {
        if (active.running) {
            active.playedMs += (nowMs - active.lastTickAtMs).coerceAtLeast(0)
        }
        active.lastTickAtMs = nowMs
    }

    private fun expectedPosition(active: Active, nowMs: Long): Long {
        val base = active.lastPositionMs ?: 0L
        if (!active.running) return base
        val elapsed = (nowMs - active.lastPositionAtMs).coerceAtLeast(0)
        return base + (elapsed * active.snapshot.speed).roundToLong()
    }

    private fun extrapolatePosition(active: Active, nowMs: Long): Long? {
        val base = active.lastPositionMs ?: return null
        if (!active.running) return base
        val elapsed = (nowMs - active.lastPositionAtMs).coerceAtLeast(0)
        return base + (elapsed * active.snapshot.speed).roundToLong()
    }

    private fun startEvent(active: Active, nowMs: Long): LogEvent = LogEvent(
        ts = nowMs,
        type = "track_started",
        fields = linkedMapOf(
            "trackKey" to active.key,
            "package" to active.snapshot.packageName,
            "sessionTag" to active.snapshot.sessionTag,
            "title" to active.snapshot.title,
            "artist" to active.snapshot.artist,
            "album" to active.snapshot.album,
            "mediaId" to active.snapshot.mediaId,
            "durationMs" to active.snapshot.durationMs,
            "positionMs" to active.snapshot.positionMs,
            "phase" to active.snapshot.phase.name.lowercase(),
            "volume" to active.volumeStart,
            "actions" to active.snapshot.actions,
            "metadataKeys" to active.snapshot.metadataKeys,
            "hasArtwork" to active.snapshot.hasArtwork,
        ),
    )

    private fun endEvent(
        active: Active,
        nowMs: Long,
        next: MediaSnapshot?,
        explicitReason: String?,
    ): LogEvent {
        val endPosition = extrapolatePosition(active, nowMs)
        val duration = active.snapshot.durationMs
        val completed = duration != null && (
            (endPosition != null && endPosition >= duration - COMPLETE_SLACK_MS) ||
                active.playedMs >= duration - COMPLETE_SLACK_MS
            )

        val reason = when {
            explicitReason != null -> explicitReason
            completed -> "completed"
            active.snapshot.phase == MediaPhase.STOPPED -> "stopped"
            next == null -> "session_lost"
            next.packageName == active.snapshot.packageName -> "skipped"
            else -> "interrupted"
        }

        return LogEvent(
            ts = nowMs,
            type = "track_ended",
            fields = linkedMapOf(
                "trackKey" to active.key,
                "package" to active.snapshot.packageName,
                "title" to active.snapshot.title,
                "artist" to active.snapshot.artist,
                "album" to active.snapshot.album,
                "mediaId" to active.snapshot.mediaId,
                "durationMs" to duration,
                "playedMs" to active.playedMs,
                "endPositionMs" to endPosition,
                "endReason" to reason,
                "completed" to (reason == "completed"),
                "startedAt" to active.startedAtMs,
                "volumeStart" to active.volumeStart,
                "volumeEnd" to lastVolume,
                "volumeLow" to active.volumeLow,
                "volumeHigh" to active.volumeHigh,
                "volumeChanges" to active.volumeChanges,
                "nextPackage" to next?.packageName,
                "nextTitle" to next?.title,
            ),
        )
    }

    private fun liveTrack(nowMs: Long, volume: Int, volumeMax: Int): LiveTrack? {
        val active = active ?: return null
        return LiveTrack(
            key = active.key,
            packageName = active.snapshot.packageName,
            title = active.snapshot.title,
            artist = active.snapshot.artist,
            album = active.snapshot.album,
            durationMs = active.snapshot.durationMs,
            positionMs = extrapolatePosition(active, nowMs),
            phase = active.snapshot.phase,
            volume = volume,
            volumeMax = volumeMax,
            startedAtMs = active.startedAtMs,
            playedMs = active.playedMs,
            mediaId = active.snapshot.mediaId,
            metadataKeys = active.snapshot.metadataKeys,
        )
    }

    private companion object {
        /** Сколько ждём возвращения сессии, прежде чем закрыть трек. */
        const val SESSION_GRACE_MS = 5_000L

        /** Скачок позиции больше этого считаем перемоткой. */
        const val SEEK_THRESHOLD_MS = 5_000L

        /** Недобор до конца трека, который ещё считается дослушиванием. */
        const val COMPLETE_SLACK_MS = 3_000L
    }
}
