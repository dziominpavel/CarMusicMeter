package com.example.carmusicmeter.data

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Превращает последовательность снимков медиасессии в события журнала.
 *
 * Трекер намеренно не знает про Android: время, снимки и громкость приходят
 * снаружи, поэтому логику пропусков, пауз и перемоток можно проверить
 * обычными JVM-тестами.
 *
 * `TickTime.wallMs` используется только для `ts` событий и `startedAt`;
 * все длительности, позиция и grace-интервалы считаются по `monoMs`.
 */
class ListeningTracker {

    private data class VolumeSegment(
        var start: Int,
        var low: Int,
        var high: Int,
        var end: Int,
        var max: Int,
        var changes: Int,
    )

    private data class Active(
        val key: String,
        var snapshot: MediaSnapshot,
        val startedAtWallMs: Long,
        var playedMs: Long,
        var running: Boolean,
        var lastTickMonoMs: Long,
        var lastTickWallMs: Long,
        var lastSeenMonoMs: Long,
        var lastPositionMs: Long?,
        var lastPositionMonoMs: Long,
        var volumeStart: Int,
        var volumeLow: Int,
        var volumeHigh: Int,
        var volumeChanges: Int,
        var volumeMax: Int,
        val volumeStats: MutableMap<String, VolumeSegment>,
        var lastCheckpointMonoMs: Long,
        var lastCheckpointPhase: MediaPhase,
    )

    data class TickResult(
        val events: List<LogEvent>,
        val live: LiveTrack?,
    )

    private var active: Active? = null
    private var lastVolume: Int? = null
    private var lastVolumeMax: Int? = null
    private var lastVolumeSource: String? = null
    private var lastMuted: Boolean? = null

    val currentPackage: String?
        get() = active?.snapshot?.packageName

    /**
     * Отдельный быстрый опрос громкости. Не создаёт событий, пока значение
     * не изменилось; при изменении привязывает его к текущему треку.
     */
    fun onVolume(
        time: TickTime,
        volume: Int,
        volumeMax: Int,
        source: String = "audio",
        muted: Boolean = false,
        sourceReason: String? = null,
    ): List<LogEvent> {
        val previous = lastVolume
        val previousMax = lastVolumeMax
        val previousSource = lastVolumeSource
        val previousMuted = lastMuted

        if (previousSource != null && previousSource != source) {
            val event = volumeSourceChanged(
                time = time,
                from = previousSource,
                to = source,
                fromValue = previous,
                toValue = volume,
                fromMax = previousMax,
                toMax = volumeMax,
                sourceReason = sourceReason,
            )
            updateLastVolume(volume, volumeMax, source, muted)
            active?.let { startVolumeSegment(it, source, volume, volumeMax) }
            return listOf(event)
        }

        updateLastVolume(volume, volumeMax, source, muted)
        if (previous == null || previousMax == null) return emptyList()

        val volumeChanged = previous != volume || previousMax != volumeMax
        val muteChanged = previousMuted != null && previousMuted != muted
        if (!volumeChanged && !muteChanged) return emptyList()

        val current = active
        if (volumeChanged) {
            current?.let { recordVolumeChange(it, source, volume, volumeMax) }
        }

        val events = mutableListOf<LogEvent>()
        if (volumeChanged) {
            events += LogEvent(
                ts = time.wallMs,
                type = "volume_changed",
                fields = linkedMapOf(
                    "previous" to previous,
                    "value" to volume,
                    "max" to volumeMax.takeIf { it > 0 },
                    "previousMax" to previousMax.takeIf { it > 0 },
                    "delta" to (volume - previous),
                    "level" to if (volumeMax > 0) volume.toDouble() / volumeMax else null,
                    "source" to source,
                    "sourceReason" to (sourceReason ?: "unspecified"),
                    "muted" to muted,
                    "trackKey" to current?.key,
                    "title" to current?.snapshot?.title,
                    "artist" to current?.snapshot?.artist,
                    "trackPositionMs" to current?.let { extrapolatePosition(it, time.monoMs) },
                ),
            )
        }
        if (muteChanged) {
            events += LogEvent(
                ts = time.wallMs,
                type = "mute_changed",
                fields = linkedMapOf(
                    "muted" to muted,
                    "volume" to volume,
                    "volumeMax" to volumeMax.takeIf { it > 0 },
                    "source" to source,
                    "sourceReason" to (sourceReason ?: "unspecified"),
                    "trackKey" to current?.key,
                    "title" to current?.snapshot?.title,
                    "artist" to current?.snapshot?.artist,
                ),
            )
        }
        return events
    }

    fun onTick(
        time: TickTime,
        snapshot: MediaSnapshot?,
        volume: Int,
        volumeMax: Int,
        source: String = "audio",
        muted: Boolean = false,
        sourceReason: String? = null,
    ): TickResult {
        val events = mutableListOf<LogEvent>()
        val previousSource = lastVolumeSource
        val previousVolume = lastVolume
        val previousMax = lastVolumeMax
        if (previousSource != null && previousSource != source) {
            events += volumeSourceChanged(
                time = time,
                from = previousSource,
                to = source,
                fromValue = previousVolume,
                toValue = volume,
                fromMax = previousMax,
                toMax = volumeMax,
                sourceReason = sourceReason,
            )
            updateLastVolume(volume, volumeMax, source, muted)
            active?.let { startVolumeSegment(it, source, volume, volumeMax) }
        } else if (lastVolume == null) {
            updateLastVolume(volume, volumeMax, source, muted)
        }

        var current = active

        if (current == null) {
            if (snapshot != null && snapshot.phase == MediaPhase.PLAYING) {
                current = start(time, snapshot, volume, volumeMax, source)
                active = current
                events += startEvent(current, time.wallMs)
            }
            return TickResult(events, liveTrack(time.monoMs, volume, volumeMax))
        }

        if (time.monoMs - current.lastTickMonoMs > MAX_TICK_GAP_MS) {
            val lastTime = TickTime(current.lastTickWallMs, current.lastTickMonoMs)
            events += endEvent(current, lastTime, next = null, explicitReason = "session_lost")
            active = null
            if (snapshot != null && snapshot.phase == MediaPhase.PLAYING) {
                val next = start(time, snapshot, volume, volumeMax, source)
                active = next
                events += startEvent(next, time.wallMs)
            }
            return TickResult(events, liveTrack(time.monoMs, volume, volumeMax))
        }

        accumulate(current, time.monoMs)
        current.lastTickWallMs = time.wallMs

        if (snapshot == null) {
            if (time.monoMs - current.lastSeenMonoMs > SESSION_GRACE_MS) {
                events += endEvent(current, time, next = null, explicitReason = "session_lost")
                active = null
            }
            return TickResult(events, liveTrack(time.monoMs, volume, volumeMax))
        }

        current.lastSeenMonoMs = time.monoMs

        if (!current.snapshot.sameTrack(snapshot)) {
            events += endEvent(current, time, next = snapshot, explicitReason = null)
            val next = start(time, snapshot, volume, volumeMax, source)
            active = next
            events += startEvent(next, time.wallMs)
            return TickResult(events, liveTrack(time.monoMs, volume, volumeMax))
        }

        val merged = preserveSameTrackFields(current.snapshot, snapshot)
        val oldPhase = current.snapshot.phase
        val newPhase = merged.phase
        val phaseChanged = oldPhase != newPhase
        val reported = merged.positionMs
        val expected = expectedPosition(current, time.monoMs)
        val positionReset = isPositionReset(newPhase, reported, expected)
        val repeated = !phaseChanged &&
            newPhase == MediaPhase.PLAYING &&
            reported != null &&
            reported <= POSITION_RESET_MAX_MS &&
            current.snapshot.durationMs != null &&
            expected >= current.snapshot.durationMs!! - COMPLETE_SLACK_MS

        if (repeated) {
            events += endEvent(current, time, next = merged, explicitReason = null)
            val next = start(time, merged, volume, volumeMax, source)
            active = next
            events += startEvent(next, time.wallMs)
            return TickResult(events, liveTrack(time.monoMs, volume, volumeMax))
        }

        if (phaseChanged) {
            val eventType = when (newPhase) {
                MediaPhase.PLAYING -> "playback_resumed"
                MediaPhase.PAUSED -> "playback_paused"
                MediaPhase.STOPPED -> "playback_stopped"
                MediaPhase.UNKNOWN -> null
            }
            if (eventType != null) {
                events += LogEvent(
                    ts = time.wallMs,
                    type = eventType,
                    fields = linkedMapOf(
                        "trackKey" to current.key,
                        "package" to current.snapshot.packageName,
                        "source" to current.snapshot.source,
                        "title" to current.snapshot.title,
                        "artist" to current.snapshot.artist,
                        "positionMs" to if (positionReset) {
                            expected
                        } else {
                            reported ?: extrapolatePosition(current, time.monoMs)
                        },
                        "playedMs" to current.playedMs,
                    ),
                )
            }
        }

        if (positionReset) {
            // Плеер на паузе/остановке отдаёт 0 вместо реальной позиции
            // (так делает, например, штатный плеер Huawei). Держим оценку,
            // иначе позиция в событиях падает в начало трека.
            current.lastPositionMs = expected
            current.lastPositionMonoMs = time.monoMs
        } else if (phaseChanged) {
            // При смене фазы позиция может «отставать» от нашей экстраполяции:
            // это не перемотка, а пауза/возобновление. Просто берём новую базу.
            reported?.let {
                current.lastPositionMs = it
                current.lastPositionMonoMs = time.monoMs
            }
        } else {
            val previous = current.lastPositionMs
            if (reported != null && previous != null && reported != previous) {
                val delta = reported - expected
                if (abs(delta) > SEEK_THRESHOLD_MS) {
                    events += LogEvent(
                        ts = time.wallMs,
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
                current.lastPositionMonoMs = time.monoMs
            } else if (reported != null && previous == null) {
                current.lastPositionMs = reported
                current.lastPositionMonoMs = time.monoMs
            }
        }

        current.running = newPhase == MediaPhase.PLAYING
        current.snapshot = merged

        return TickResult(events, liveTrack(time.monoMs, volume, volumeMax))
    }

    /**
     * Контрольная точка активного трека: раз в минуту во время PLAYING и один
     * раз при переходе в PAUSED/STOPPED. Служба добавляет событие в свою пачку.
     */
    fun checkpoint(
        time: TickTime,
        volume: Int,
        volumeMax: Int,
    ): LogEvent? {
        val current = active ?: return null
        val phase = current.snapshot.phase
        val due = current.running &&
            phase == MediaPhase.PLAYING &&
            time.monoMs - current.lastCheckpointMonoMs >= CHECKPOINT_INTERVAL_MS
        val becameInactive = phase != MediaPhase.PLAYING &&
            current.lastCheckpointPhase == MediaPhase.PLAYING

        current.lastCheckpointPhase = phase
        if (!due && !becameInactive) return null

        current.lastCheckpointMonoMs = time.monoMs
        return progressEvent(current, time, volume, volumeMax)
    }

    /** Закрыть текущий трек, например при остановке службы. */
    fun finish(
        time: TickTime,
        reason: String = "service_stopped",
    ): List<LogEvent> {
        val current = active ?: return emptyList()
        accumulate(current, time.monoMs)
        val event = endEvent(current, time, next = null, explicitReason = reason)
        active = null
        return listOf(event)
    }

    /**
     * Снимок открытого прослушивания для сохранения между запусками.
     *
     * Возвращает `null`, если трек не активен: сохранять тогда нечего.
     */
    fun openPlay(bootId: String, time: TickTime): OpenPlay? {
        val current = active ?: return null
        return OpenPlay(
            bootId = bootId,
            trackKey = current.key,
            packageName = current.snapshot.packageName,
            source = current.snapshot.source,
            title = current.snapshot.title,
            artist = current.snapshot.artist,
            album = current.snapshot.album,
            mediaId = current.snapshot.mediaId,
            durationMs = current.snapshot.durationMs,
            positionMs = extrapolatePosition(current, time.monoMs),
            playedMs = current.playedMs,
            startedAtWallMs = current.startedAtWallMs,
            lastTsWallMs = current.lastTickWallMs,
            volumeStart = current.volumeStart,
            volumeLow = current.volumeLow,
            volumeHigh = current.volumeHigh,
            volumeEnd = lastVolume ?: current.volumeStart,
            volumeMax = current.volumeMax,
            volumeChanges = current.volumeChanges,
            volumeSource = lastVolumeSource ?: "audio",
            volumeStats = current.volumeStats.mapValues { (_, segment) ->
                OpenPlayVolume(
                    start = segment.start,
                    low = segment.low,
                    high = segment.high,
                    end = segment.end,
                    max = segment.max,
                    changes = segment.changes,
                )
            },
        )
    }

    /**
     * Закрывает прослушивание, оставшееся от предыдущего процесса.
     *
     * Событие строится по сохранённым полям, поэтому не теряет длительность,
     * путь и статистику громкости. `playedMs` берётся из контрольной точки —
     * время, пока приложение не работало, не досчитывается.
     */
    fun closeRestored(open: OpenPlay, time: TickTime): LogEvent {
        val snapshot = MediaSnapshot(
            packageName = open.packageName,
            sessionTag = "stock",
            title = open.title,
            artist = open.artist,
            album = open.album,
            durationMs = open.durationMs,
            positionMs = open.positionMs,
            phase = MediaPhase.PLAYING,
            mediaId = open.mediaId,
            source = open.source,
        )
        val restored = Active(
            key = open.trackKey,
            snapshot = snapshot,
            startedAtWallMs = open.startedAtWallMs,
            playedMs = open.playedMs,
            running = false,
            lastTickMonoMs = time.monoMs,
            lastTickWallMs = open.lastTsWallMs,
            lastSeenMonoMs = time.monoMs,
            lastPositionMs = open.positionMs,
            lastPositionMonoMs = time.monoMs,
            volumeStart = open.volumeStart,
            volumeLow = open.volumeLow,
            volumeHigh = open.volumeHigh,
            volumeChanges = open.volumeChanges,
            volumeMax = open.volumeMax,
            volumeStats = open.volumeStats.mapValuesTo(mutableMapOf()) { (_, value) ->
                VolumeSegment(
                    start = value.start,
                    low = value.low,
                    high = value.high,
                    end = value.end,
                    max = value.max,
                    changes = value.changes,
                )
            },
            lastCheckpointMonoMs = time.monoMs,
            lastCheckpointPhase = MediaPhase.PLAYING,
        )
        lastVolume = open.volumeEnd
        lastVolumeMax = open.volumeMax
        lastVolumeSource = open.volumeSource
        return endEvent(restored, time, next = null, explicitReason = "session_lost")
    }

    /**
     * Тот же трек может прийти с неполными полями: плеер присылает переходный
     * intent без duration/mediaId. Не теряем уже известные значения.
     */
    private fun preserveSameTrackFields(
        old: MediaSnapshot,
        new: MediaSnapshot,
    ): MediaSnapshot {
        if (new.durationMs == null) {
            return new.copy(
                durationMs = old.durationMs,
                mediaId = old.mediaId ?: new.mediaId,
            )
        }
        if (new.mediaId == null) {
            return new.copy(mediaId = old.mediaId)
        }
        return new
    }

    private fun start(
        time: TickTime,
        snapshot: MediaSnapshot,
        volume: Int,
        volumeMax: Int,
        source: String,
    ): Active {
        val segment = VolumeSegment(
            start = volume,
            low = volume,
            high = volume,
            end = volume,
            max = volumeMax,
            changes = 0,
        )
        return Active(
            key = snapshot.trackKey,
            snapshot = snapshot,
            startedAtWallMs = time.wallMs,
            playedMs = 0L,
            running = snapshot.phase == MediaPhase.PLAYING,
            lastTickMonoMs = time.monoMs,
            lastTickWallMs = time.wallMs,
            lastSeenMonoMs = time.monoMs,
            lastPositionMs = snapshot.positionMs,
            lastPositionMonoMs = time.monoMs,
            volumeStart = volume,
            volumeLow = volume,
            volumeHigh = volume,
            volumeChanges = 0,
            volumeMax = volumeMax,
            volumeStats = mutableMapOf(source to segment),
            lastCheckpointMonoMs = time.monoMs,
            lastCheckpointPhase = snapshot.phase,
        )
    }

    private fun updateLastVolume(volume: Int, volumeMax: Int, source: String, muted: Boolean) {
        lastVolume = volume
        lastVolumeMax = volumeMax
        lastVolumeSource = source
        lastMuted = muted
    }

    private fun startVolumeSegment(active: Active, source: String, volume: Int, volumeMax: Int) {
        val segment = active.volumeStats[source]
        if (segment == null) {
            active.volumeStats[source] = VolumeSegment(
                start = volume,
                low = volume,
                high = volume,
                end = volume,
                max = volumeMax,
                changes = 0,
            )
        } else {
            // Источник вернулся: сохраняем его прежний диапазон и число изменений.
            segment.end = volume
            segment.low = minOf(segment.low, volume)
            segment.high = maxOf(segment.high, volume)
            segment.max = volumeMax
        }
        val current = active.volumeStats.getValue(source)
        active.volumeStart = current.start
        active.volumeLow = current.low
        active.volumeHigh = current.high
        active.volumeChanges = current.changes
        active.volumeMax = volumeMax
    }

    private fun recordVolumeChange(active: Active, source: String, volume: Int, volumeMax: Int) {
        val segment = active.volumeStats.getOrPut(source) {
            VolumeSegment(volume, volume, volume, volume, volumeMax, 0)
        }
        segment.end = volume
        segment.low = minOf(segment.low, volume)
        segment.high = maxOf(segment.high, volume)
        segment.max = volumeMax
        segment.changes += 1
        active.volumeStart = segment.start
        active.volumeLow = segment.low
        active.volumeHigh = segment.high
        active.volumeChanges = segment.changes
        active.volumeMax = volumeMax
    }

    private fun volumeSourceChanged(
        time: TickTime,
        from: String,
        to: String,
        fromValue: Int?,
        toValue: Int,
        fromMax: Int?,
        toMax: Int,
        sourceReason: String?,
    ): LogEvent {
        val current = active
        return LogEvent(
            ts = time.wallMs,
            type = "volume_source_changed",
            fields = linkedMapOf(
                "from" to from,
                "to" to to,
                "fromValue" to fromValue,
                "toValue" to toValue,
                "fromMax" to fromMax?.takeIf { it > 0 },
                "toMax" to toMax.takeIf { it > 0 },
                "sourceReason" to (sourceReason ?: "unspecified"),
                "trackKey" to current?.key,
                "title" to current?.snapshot?.title,
                "artist" to current?.snapshot?.artist,
            ),
        )
    }

    private fun accumulate(active: Active, monoMs: Long) {
        if (active.running) {
            active.playedMs += (monoMs - active.lastTickMonoMs).coerceAtLeast(0)
        }
        active.lastTickMonoMs = monoMs
    }

    /**
     * Плеер на паузе или остановке отдаёт почти ноль вместо реальной позиции.
     * Это не перемотка: так делает, например, штатный плеер Huawei.
     */
    private fun isPositionReset(phase: MediaPhase, reported: Long?, expected: Long): Boolean =
        (phase == MediaPhase.PAUSED || phase == MediaPhase.STOPPED) &&
            reported != null &&
            reported <= POSITION_RESET_MAX_MS &&
            expected > SEEK_THRESHOLD_MS

    private fun expectedPosition(active: Active, monoMs: Long): Long {
        val base = active.lastPositionMs ?: 0L
        if (!active.running) return base
        val elapsed = (monoMs - active.lastPositionMonoMs).coerceAtLeast(0)
        return base + (elapsed * active.snapshot.speed).roundToLong()
    }

    private fun extrapolatePosition(active: Active, monoMs: Long): Long? {
        val base = active.lastPositionMs ?: return null
        if (!active.running) return base
        val elapsed = (monoMs - active.lastPositionMonoMs).coerceAtLeast(0)
        return base + (elapsed * active.snapshot.speed).roundToLong()
    }

    private fun startEvent(active: Active, wallMs: Long): LogEvent = LogEvent(
        ts = wallMs,
        type = "track_started",
        fields = linkedMapOf(
            "trackKey" to active.key,
            "package" to active.snapshot.packageName,
            "sessionTag" to active.snapshot.sessionTag,
            "source" to active.snapshot.source,
            "title" to active.snapshot.title,
            "artist" to active.snapshot.artist,
            "album" to active.snapshot.album,
            "mediaId" to active.snapshot.mediaId,
            "durationMs" to active.snapshot.durationMs,
            "positionMs" to active.snapshot.positionMs,
            "phase" to active.snapshot.phase.name.lowercase(),
            "volume" to active.volumeStart,
            "volumeMax" to active.volumeMax.takeIf { it > 0 },
            "volumeSource" to lastVolumeSource,
            "actions" to active.snapshot.actions,
            "metadataKeys" to active.snapshot.metadataKeys,
            "hasArtwork" to active.snapshot.hasArtwork,
        ),
    )

    private fun progressEvent(
        active: Active,
        time: TickTime,
        volume: Int,
        volumeMax: Int,
    ): LogEvent = LogEvent(
        ts = time.wallMs,
        type = "track_progress",
        fields = linkedMapOf(
            "trackKey" to active.key,
            "package" to active.snapshot.packageName,
            "source" to active.snapshot.source,
            "title" to active.snapshot.title,
            "artist" to active.snapshot.artist,
            "positionMs" to extrapolatePosition(active, time.monoMs),
            "playedMs" to active.playedMs,
            "durationMs" to active.snapshot.durationMs,
            "volume" to volume,
            "volumeMax" to volumeMax.takeIf { it > 0 },
            "volumeSource" to lastVolumeSource,
            "phase" to active.snapshot.phase.name.lowercase(),
            "startedAt" to active.startedAtWallMs,
        ),
    )

    private fun endEvent(
        active: Active,
        time: TickTime,
        next: MediaSnapshot?,
        explicitReason: String?,
    ): LogEvent {
        val endPosition = extrapolatePosition(active, time.monoMs)
        val duration = active.snapshot.durationMs
        val completed = duration != null && (
            (endPosition != null && endPosition >= duration - COMPLETE_SLACK_MS) ||
                active.playedMs >= duration - COMPLETE_SLACK_MS
            )

        val reason = when {
            explicitReason != null -> explicitReason
            completed -> "completed"
            active.playedMs < NOT_PLAYED_MAX_MS -> "not_played"
            active.snapshot.phase == MediaPhase.STOPPED -> "stopped"
            next == null -> "session_lost"
            active.snapshot.source == StockPlayerSources.ACCESSIBILITY && duration == null -> "unknown"
            next.packageName == active.snapshot.packageName -> "skipped"
            else -> "interrupted"
        }

        val rawPlayedMs = active.playedMs
        val technical = reason in setOf("session_lost", "service_stopped", "stopped", "unknown")
        val playedMs = if (technical && duration != null) {
            minOf(rawPlayedMs, duration)
        } else {
            rawPlayedMs
        }

        return LogEvent(
            ts = time.wallMs,
            type = "track_ended",
            fields = linkedMapOf(
                "trackKey" to active.key,
                "package" to active.snapshot.packageName,
                "source" to active.snapshot.source,
                "title" to active.snapshot.title,
                "artist" to active.snapshot.artist,
                "album" to active.snapshot.album,
                "mediaId" to active.snapshot.mediaId,
                "durationMs" to duration,
                "playedMs" to playedMs,
                "rawPlayedMs" to rawPlayedMs,
                "endPositionMs" to endPosition,
                "endReason" to reason,
                "completed" to (reason == "completed"),
                "startedAt" to active.startedAtWallMs,
                "volumeStart" to active.volumeStart,
                "volumeEnd" to lastVolume,
                "volumeLow" to active.volumeLow,
                "volumeHigh" to active.volumeHigh,
                "volumeChanges" to active.volumeChanges,
                "volumeMax" to active.volumeMax.takeIf { it > 0 },
                "volumeSource" to lastVolumeSource,
                "volumeStats" to active.volumeStats.mapValues { (_, segment) ->
                    mapOf(
                        "start" to segment.start,
                        "low" to segment.low,
                        "high" to segment.high,
                        "end" to segment.end,
                        "max" to segment.max.takeIf { it > 0 },
                        "changes" to segment.changes,
                    )
                },
                "nextPackage" to next?.packageName,
                "nextTitle" to next?.title,
            ),
        )
    }

    private fun liveTrack(monoMs: Long, volume: Int, volumeMax: Int): LiveTrack? {
        val active = active ?: return null
        return LiveTrack(
            key = active.key,
            packageName = active.snapshot.packageName,
            title = active.snapshot.title,
            artist = active.snapshot.artist,
            album = active.snapshot.album,
            durationMs = active.snapshot.durationMs,
            positionMs = extrapolatePosition(active, monoMs),
            phase = active.snapshot.phase,
            volume = volume,
            volumeMax = volumeMax,
            startedAtMs = active.startedAtWallMs,
            playedMs = active.playedMs,
            mediaId = active.snapshot.mediaId,
            metadataKeys = active.snapshot.metadataKeys,
            source = active.snapshot.source,
            volumeSource = lastVolumeSource ?: "audio",
        )
    }

    private companion object {
        /** Сколько ждём возвращения сессии, прежде чем закрыть трек. */
        const val SESSION_GRACE_MS = 5_000L

        /** Разрыв между тиками, который считается сном/doze, а не задержкой. */
        const val MAX_TICK_GAP_MS = 120_000L

        /** Скачок позиции больше этого считаем перемоткой. */
        const val SEEK_THRESHOLD_MS = 5_000L

        /** Недобор до конца трека, который ещё считается дослушиванием. */
        const val COMPLETE_SLACK_MS = 3_000L

        /** Позиция на паузе ниже этого значения считается сбросом в ноль. */
        const val POSITION_RESET_MAX_MS = 1_000L

        /** Слишком короткий трек не считаем настоящим прослушиванием. */
        const val NOT_PLAYED_MAX_MS = 3_000L

        /** Как часто писать контрольную точку активного трека. */
        const val CHECKPOINT_INTERVAL_MS = 60_000L
    }
}
