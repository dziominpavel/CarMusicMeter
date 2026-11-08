package com.example.carmusicmeter.data

import org.json.JSONObject

/**
 * Разбирает историю из `events-*.jsonl` в факты прослушиваний.
 *
 * `track_ended` уже содержит `playedMs`, `endReason` и статистику громкости,
 * поэтому builder не повторяет конечный автомат трекера: он восстанавливает
 * порядок и даты по `bootId`/`monoMs`, поправляет скачки часов, схлопывает
 * двойные закрытия и добавляет открытые прослушивания.
 */
object PlayBuilder {

    const val VERSION = 1

    private const val CONFIRMATION_HORIZON_MS = 365L * 24 * 60 * 60 * 1000

    data class Result(
        val plays: List<PlayRecord>,
        val malformed: Int = 0,
    )

    fun build(
        lines: Sequence<String>,
        deviceId: String = "",
        appVersion: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): Result {
        var malformed = 0
        val rows = ArrayList<Row>()
        lines.forEach { line ->
            if (line.isBlank()) return@forEach
            val json = runCatching { JSONObject(line) }.getOrElse {
                malformed += 1
                return@forEach
            }
            rows += Row(
                ts = json.optLong("ts", 0L),
                mono = json.optLongOrNull("monoMs"),
                bootId = json.optStringOrNull("bootId"),
                verified = if (json.has("clockVerified")) json.optBoolean("clockVerified") else null,
                type = json.optString("type", ""),
                json = json,
            )
        }
        if (rows.isEmpty()) return Result(emptyList(), malformed)

        val jumps = clockJumps(rows)
        val starts = rows
            .filter { it.type == "track_started" }
            .associateBy { startKey(it) }
        val seeks = rows.filter { it.type == "seek" }
        val progress = rows.filter { it.type == "track_progress" }

        val plays = ArrayList<Pair<Pair<String, Long>, PlayRecord>>()
        rows.filter { it.type == "track_ended" }.forEach { row ->
            val trackKey = row.json.optString("trackKey", "")
            val startedRaw = row.json.optLong("startedAt", row.ts)
            val startRow = starts[startKey(row.bootId, trackKey, startedRaw)]
            val startedAt = correctedTs(
                ts = startedRaw,
                mono = startRow?.mono ?: row.mono,
                bootId = row.bootId,
                jumps = jumps,
            )
            val endedAt = correctedTs(row.ts, row.mono, row.bootId, jumps)
            val confirmed = confirmed(row, jumps, nowMs) && startRow != null
            val base = runCatching { PlayRecord.fromJson(row.json) }.getOrElse {
                malformed += 1
                return@forEach
            }
            val seeksInPlay = seeks.count { seek ->
                seek.bootId == row.bootId &&
                    seek.json.optString("trackKey", "") == trackKey &&
                    correctedTs(seek.ts, seek.mono, seek.bootId, jumps) in startedAt..endedAt
            }
            val seekBack = seeks.count { seek ->
                seek.bootId == row.bootId &&
                    seek.json.optString("trackKey", "") == trackKey &&
                    correctedTs(seek.ts, seek.mono, seek.bootId, jumps) in startedAt..endedAt &&
                    seek.json.optLong("deltaMs", 0L) < 0
            }
            val play = base.copy(
                playId = row.json.optStringOrNull("playId")
                    ?.takeIf { it.isNotBlank() }
                    ?: PlayRecord.syntheticId(row.bootId.orEmpty(), startedRaw, trackKey),
                origin = "history",
                bootId = row.bootId.orEmpty(),
                startedAt = startedAt,
                endedAt = endedAt,
                clockVerified = confirmed,
                seekBack = seekBack,
                seekForward = seeksInPlay - seekBack,
                deviceId = deviceId,
                appVersion = appVersion,
                open = false,
            )
            plays += (trackKey to startedRaw) to play
        }

        // Одно прослушивание — один факт. При двойном закрытии (старая сборка
        // закрыла из памяти, новая — из checkpoint) оставляем не-restored запись.
        val deduped = plays.groupBy({ it.first }, { it.second }).mapValues { (_, items) ->
            items.filterNot { it.restored }.minByOrNull { it.endedAt }
                ?: items.minByOrNull { it.endedAt }!!
        }.values

        val closedStarts = plays.map { it.first }.toSet()
        val openPlays = ArrayList<PlayRecord>()
        rows.filter { it.type == "track_started" }.forEach { start ->
            val trackKey = start.json.optString("trackKey", "")
            val key = trackKey to start.ts
            if (key in closedStarts) return@forEach
            val lastProgress = progress
                .filter {
                    it.bootId == start.bootId &&
                        it.json.optString("trackKey", "") == trackKey &&
                        it.json.optLong("startedAt", -1L) == start.ts
                }
                .maxByOrNull { it.ts }
            openPlays += openPlay(start, lastProgress, jumps, deviceId, appVersion, nowMs)
        }

        val all = (deduped + openPlays)
            .sortedWith(compareBy<PlayRecord> { it.startedAt }.thenBy { it.playId })
        return Result(all, malformed)
    }

    private fun openPlay(
        start: Row,
        progress: Row?,
        jumps: Map<String, List<Jump>>,
        deviceId: String,
        appVersion: String,
        nowMs: Long,
    ): PlayRecord {
        val source = progress ?: start
        val trackKey = start.json.optString("trackKey", "")
        val startedAt = correctedTs(start.ts, start.mono, start.bootId, jumps)
        val base = runCatching { PlayRecord.fromJson(source.json) }.getOrNull()
        val volume = source.json.optIntOrNull("volume")
        val volumeMax = source.json.optIntOrNull("volumeMax")
        return PlayRecord(
            playId = start.json.optStringOrNull("playId")
                ?.takeIf { it.isNotBlank() }
                ?: PlayRecord.syntheticId(start.bootId.orEmpty(), start.ts, trackKey),
            origin = "history",
            bootId = start.bootId.orEmpty(),
            trackKey = trackKey,
            packageName = base?.packageName ?: start.json.optString("package", ""),
            source = base?.source ?: start.json.optString("source", ""),
            title = base?.title ?: start.json.optStringOrNull("title"),
            artist = base?.artist ?: start.json.optStringOrNull("artist"),
            album = base?.album ?: start.json.optStringOrNull("album"),
            mediaId = base?.mediaId ?: start.json.optStringOrNull("mediaId"),
            durationMs = base?.durationMs ?: start.json.optLongOrNull("durationMs"),
            startedAt = startedAt,
            endedAt = correctedTs(source.ts, source.mono, source.bootId, jumps),
            playedMs = source.json.optLong("playedMs", 0L),
            rawPlayedMs = source.json.optLong("playedMs", 0L),
            endPositionMs = source.json.optLongOrNull("positionMs"),
            endReason = "open",
            completed = false,
            restored = false,
            clockVerified = confirmed(start, jumps, nowMs),
            volumeStart = volume,
            volumeEnd = volume,
            volumeLow = volume,
            volumeHigh = volume,
            volumeMax = volumeMax?.takeIf { it > 0 },
            volumeSource = source.json.optStringOrNull("volumeSource"),
            volumeChanges = 0,
            volumeStats = emptyMap(),
            seekBack = 0,
            seekForward = 0,
            deviceId = deviceId,
            appVersion = appVersion,
            open = true,
        )
    }

    private data class Row(
        val ts: Long,
        val mono: Long?,
        val bootId: String?,
        val verified: Boolean?,
        val type: String,
        val json: JSONObject,
    )

    private data class Jump(val mono: Long, val wallDelta: Long)

    private fun clockJumps(rows: List<Row>): Map<String, List<Jump>> =
        rows.filter { it.type == "clock_changed" }
            .mapNotNull { row ->
                val bootId = row.bootId ?: return@mapNotNull null
                val mono = row.mono ?: return@mapNotNull null
                val delta = row.json.optLongOrNull("wallDelta")
                    ?: row.json.optLongOrNull("shiftMs")
                    ?: return@mapNotNull null
                bootId to Jump(mono, delta)
            }
            .groupBy({ it.first }, { it.second })

    private fun correctedTs(
        ts: Long,
        mono: Long?,
        bootId: String?,
        jumps: Map<String, List<Jump>>,
    ): Long {
        if (bootId == null || mono == null) return ts
        val shift = jumps[bootId].orEmpty()
            .filter { it.mono > mono }
            .sumOf { it.wallDelta }
        return ts + shift
    }

    private fun confirmed(
        row: Row,
        jumps: Map<String, List<Jump>>,
        nowMs: Long,
    ): Boolean = when (row.verified) {
        true -> true
        false -> {
            val bootId = row.bootId
            val mono = row.mono
            if (bootId == null || mono == null) {
                false
            } else {
                jumps[bootId].orEmpty().any { it.mono > mono }
            }
        }
        null -> correctedTs(row.ts, row.mono, row.bootId, jumps) >=
            nowMs - CONFIRMATION_HORIZON_MS
    }

    private fun startKey(row: Row): Triple<String?, String, Long> =
        Triple(row.bootId, row.json.optString("trackKey", ""), row.ts)

    private fun startKey(bootId: String?, trackKey: String, startedAt: Long) =
        Triple(bootId, trackKey, startedAt)
}

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
