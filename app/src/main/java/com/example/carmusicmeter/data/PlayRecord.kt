package com.example.carmusicmeter.data

import java.util.UUID
import org.json.JSONObject

/** Статистика громкости одного источника внутри прослушивания. */
data class PlayVolume(
    val start: Int,
    val low: Int,
    val high: Int,
    val end: Int,
    val max: Int,
    val changes: Int,
)

/**
 * Один факт прослушивания: стабильная запись, из которой считаются метрики,
 * отчёт и ZIP. В отличие от [LogEvent], схема факта меняется только вместе с
 * [SCHEMA_VERSION] и не зависит от диагностических событий.
 */
data class PlayRecord(
    val playId: String,
    val origin: String,
    val bootId: String,
    val trackKey: String,
    val packageName: String,
    val source: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val mediaId: String?,
    val durationMs: Long?,
    val startedAt: Long,
    val endedAt: Long,
    val playedMs: Long,
    val rawPlayedMs: Long,
    val endPositionMs: Long?,
    val endReason: String,
    val completed: Boolean,
    val restored: Boolean,
    val clockVerified: Boolean,
    val volumeStart: Int?,
    val volumeEnd: Int?,
    val volumeLow: Int?,
    val volumeHigh: Int?,
    val volumeMax: Int?,
    val volumeSource: String?,
    val volumeChanges: Int,
    val volumeStats: Map<String, PlayVolume>,
    val seekBack: Int,
    val seekForward: Int,
    val deviceId: String,
    val appVersion: String,
    val open: Boolean = false,
    val playsSchemaVersion: Int = SCHEMA_VERSION,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("playsSchemaVersion", playsSchemaVersion)
        put("playId", playId)
        put("origin", origin)
        put("bootId", bootId)
        put("trackKey", trackKey)
        put("package", packageName)
        put("source", source)
        put("title", title ?: JSONObject.NULL)
        put("artist", artist ?: JSONObject.NULL)
        put("album", album ?: JSONObject.NULL)
        put("mediaId", mediaId ?: JSONObject.NULL)
        put("durationMs", durationMs ?: JSONObject.NULL)
        put("startedAt", startedAt)
        put("endedAt", endedAt)
        put("playedMs", playedMs)
        put("rawPlayedMs", rawPlayedMs)
        put("endPositionMs", endPositionMs ?: JSONObject.NULL)
        put("endReason", endReason)
        put("completed", completed)
        put("restored", restored)
        put("clockVerified", clockVerified)
        put("volumeStart", volumeStart ?: JSONObject.NULL)
        put("volumeEnd", volumeEnd ?: JSONObject.NULL)
        put("volumeLow", volumeLow ?: JSONObject.NULL)
        put("volumeHigh", volumeHigh ?: JSONObject.NULL)
        put("volumeMax", volumeMax ?: JSONObject.NULL)
        put("volumeSource", volumeSource ?: JSONObject.NULL)
        put("volumeChanges", volumeChanges)
        put(
            "volumeStats",
            JSONObject().apply {
                volumeStats.forEach { (name, value) ->
                    put(
                        name,
                        JSONObject().apply {
                            put("start", value.start)
                            put("low", value.low)
                            put("high", value.high)
                            put("end", value.end)
                            put("max", value.max)
                            put("changes", value.changes)
                        },
                    )
                }
            },
        )
        put("seekBack", seekBack)
        put("seekForward", seekForward)
        put("deviceId", deviceId)
        put("appVersion", appVersion)
        put("open", open)
    }

    fun toJsonLine(): String = toJson().toString()

    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(raw: String?): PlayRecord? {
            if (raw.isNullOrBlank()) return null
            return runCatching { fromJson(JSONObject(raw)) }.getOrNull()
        }

        fun fromJson(json: JSONObject): PlayRecord {
            val trackKey = json.optString("trackKey", "")
            val startedAt = json.optLong("startedAt", json.optLong("ts", 0L))
            return PlayRecord(
                playId = json.optString("playId", "")
                    .ifBlank { syntheticId(json.optString("bootId", ""), startedAt, trackKey) },
                origin = json.optString("origin", "history"),
                bootId = json.optString("bootId", ""),
                trackKey = trackKey,
                packageName = json.optString("package", ""),
                source = json.optString("source", ""),
                title = json.optStringOrNull("title"),
                artist = json.optStringOrNull("artist"),
                album = json.optStringOrNull("album"),
                mediaId = json.optStringOrNull("mediaId"),
                durationMs = json.optLongOrNull("durationMs"),
                startedAt = startedAt,
                endedAt = json.optLong("endedAt", startedAt),
                playedMs = json.optLong("playedMs", 0L),
                rawPlayedMs = json.optLong("rawPlayedMs", json.optLong("playedMs", 0L)),
                endPositionMs = json.optLongOrNull("endPositionMs"),
                endReason = json.optString("endReason", "unknown"),
                completed = json.optBoolean("completed", false),
                restored = json.optBoolean("restored", false),
                clockVerified = json.optBoolean("clockVerified", true),
                volumeStart = json.optIntOrNull("volumeStart"),
                volumeEnd = json.optIntOrNull("volumeEnd"),
                volumeLow = json.optIntOrNull("volumeLow"),
                volumeHigh = json.optIntOrNull("volumeHigh"),
                volumeMax = json.optIntOrNull("volumeMax"),
                volumeSource = json.optStringOrNull("volumeSource"),
                volumeChanges = json.optInt("volumeChanges", 0),
                volumeStats = json.optJSONObject("volumeStats")?.let(::parseVolumeStats).orEmpty(),
                seekBack = json.optInt("seekBack", 0),
                seekForward = json.optInt("seekForward", 0),
                deviceId = json.optString("deviceId", ""),
                appVersion = json.optString("appVersion", ""),
                open = json.optBoolean("open", false),
                playsSchemaVersion = json.optInt("playsSchemaVersion", SCHEMA_VERSION),
            )
        }

        fun fromTrackEnded(
            event: LogEvent,
            bootId: String,
            deviceId: String,
            appVersion: String,
            clockVerified: Boolean,
            origin: String = "live",
        ): PlayRecord? {
            if (event.type != "track_ended") return null
            val fields = event.fields
            val trackKey = fields["trackKey"] as? String ?: return null
            val startedAt = longOf(fields["startedAt"]) ?: event.ts
            val playedMs = longOf(fields["playedMs"]) ?: 0L
            val playId = (fields["playId"] as? String)
                ?.takeIf { it.isNotBlank() }
                ?: syntheticId(bootId, startedAt, trackKey)
            return PlayRecord(
                playId = playId,
                origin = origin,
                bootId = bootId,
                trackKey = trackKey,
                packageName = fields["package"] as? String ?: "",
                source = fields["source"] as? String ?: "",
                title = fields["title"] as? String,
                artist = fields["artist"] as? String,
                album = fields["album"] as? String,
                mediaId = fields["mediaId"] as? String,
                durationMs = longOf(fields["durationMs"]),
                startedAt = startedAt,
                endedAt = event.ts,
                playedMs = playedMs,
                rawPlayedMs = longOf(fields["rawPlayedMs"]) ?: playedMs,
                endPositionMs = longOf(fields["endPositionMs"]),
                endReason = fields["endReason"] as? String ?: "unknown",
                completed = fields["completed"] as? Boolean
                    ?: (fields["endReason"] == "completed"),
                restored = fields["restored"] as? Boolean ?: false,
                clockVerified = clockVerified,
                volumeStart = intOf(fields["volumeStart"]),
                volumeEnd = intOf(fields["volumeEnd"]),
                volumeLow = intOf(fields["volumeLow"]),
                volumeHigh = intOf(fields["volumeHigh"]),
                volumeMax = intOf(fields["volumeMax"]),
                volumeSource = fields["volumeSource"] as? String,
                volumeChanges = intOf(fields["volumeChanges"]) ?: 0,
                volumeStats = parseVolumeStats(fields["volumeStats"]),
                seekBack = intOf(fields["seekBack"]) ?: 0,
                seekForward = intOf(fields["seekForward"]) ?: 0,
                deviceId = deviceId,
                appVersion = appVersion,
                open = false,
            )
        }

        fun fromOpenPlay(
            open: OpenPlay,
            deviceId: String,
            appVersion: String,
            clockVerified: Boolean,
        ): PlayRecord = PlayRecord(
            playId = open.playId.ifBlank {
                syntheticId(open.bootId, open.startedAtWallMs, open.trackKey)
            },
            origin = "live",
            bootId = open.bootId,
            trackKey = open.trackKey,
            packageName = open.packageName,
            source = open.source,
            title = open.title,
            artist = open.artist,
            album = open.album,
            mediaId = open.mediaId,
            durationMs = open.durationMs,
            startedAt = open.startedAtWallMs,
            endedAt = open.lastTsWallMs,
            playedMs = open.playedMs,
            rawPlayedMs = open.playedMs,
            endPositionMs = open.positionMs,
            endReason = "open",
            completed = false,
            restored = false,
            clockVerified = clockVerified,
            volumeStart = open.volumeStart,
            volumeEnd = open.volumeEnd,
            volumeLow = open.volumeLow,
            volumeHigh = open.volumeHigh,
            volumeMax = open.volumeMax.takeIf { it > 0 },
            volumeSource = open.volumeSource,
            volumeChanges = open.volumeChanges,
            volumeStats = open.volumeStats.mapValues { (_, value) ->
                PlayVolume(
                    start = value.start,
                    low = value.low,
                    high = value.high,
                    end = value.end,
                    max = value.max,
                    changes = value.changes,
                )
            },
            seekBack = open.seekBack,
            seekForward = open.seekForward,
            deviceId = deviceId,
            appVersion = appVersion,
            open = true,
        )

        fun syntheticId(bootId: String, startedAt: Long, trackKey: String): String =
            "hist-" + UUID.nameUUIDFromBytes("$bootId|$startedAt|$trackKey".toByteArray())
                .toString()

        private fun parseVolumeStats(value: Any?): Map<String, PlayVolume> = when (value) {
            is JSONObject -> value.keys().asSequence().associateWith { name ->
                parseVolume(value.getJSONObject(name))
            }
            is Map<*, *> -> value.entries.mapNotNull { (key, item) ->
                val name = key as? String ?: return@mapNotNull null
                val volume = when (item) {
                    is JSONObject -> parseVolume(item)
                    is Map<*, *> -> PlayVolume(
                        start = intOf(item["start"]) ?: 0,
                        low = intOf(item["low"]) ?: 0,
                        high = intOf(item["high"]) ?: 0,
                        end = intOf(item["end"]) ?: 0,
                        max = intOf(item["max"]) ?: 0,
                        changes = intOf(item["changes"]) ?: 0,
                    )
                    else -> null
                } ?: return@mapNotNull null
                name to volume
            }.toMap()
            else -> emptyMap()
        }

        private fun parseVolume(json: JSONObject): PlayVolume = PlayVolume(
            start = json.optInt("start", 0),
            low = json.optInt("low", 0),
            high = json.optInt("high", 0),
            end = json.optInt("end", 0),
            max = json.optInt("max", 0),
            changes = json.optInt("changes", 0),
        )
    }
}

/** CSV-представление фактов для Excel и ИИ. */
object PlayCsv {

    val header: List<String> = listOf(
        "playsSchemaVersion", "playId", "origin", "bootId", "trackKey",
        "package", "source", "title", "artist", "album", "mediaId",
        "durationMs", "startedAt", "endedAt", "playedMs", "rawPlayedMs",
        "endPositionMs", "endReason", "completed", "restored", "clockVerified",
        "volumeStart", "volumeEnd", "volumeLow", "volumeHigh", "volumeMax",
        "volumeSource", "volumeChanges",
        "mcuStart", "mcuLow", "mcuHigh", "mcuEnd", "mcuMax", "mcuChanges",
        "audioStart", "audioLow", "audioHigh", "audioEnd", "audioMax", "audioChanges",
        "seekBack", "seekForward", "deviceId", "appVersion", "open",
    )

    fun render(plays: List<PlayRecord>): String = buildString {
        append(header.joinToString(","))
        append("\r\n")
        plays.forEach { play ->
            append(row(play).joinToString(",") { escape(it) })
            append("\r\n")
        }
    }

    fun row(play: PlayRecord): List<String> {
        val mcu = play.volumeStats["mcu"]
        val audio = play.volumeStats["audio"]
        return listOf(
            play.playsSchemaVersion.toString(),
            play.playId,
            play.origin,
            play.bootId,
            play.trackKey,
            play.packageName,
            play.source,
            play.title.orEmpty(),
            play.artist.orEmpty(),
            play.album.orEmpty(),
            play.mediaId.orEmpty(),
            play.durationMs?.toString().orEmpty(),
            play.startedAt.toString(),
            play.endedAt.toString(),
            play.playedMs.toString(),
            play.rawPlayedMs.toString(),
            play.endPositionMs?.toString().orEmpty(),
            play.endReason,
            play.completed.toString(),
            play.restored.toString(),
            play.clockVerified.toString(),
            play.volumeStart?.toString().orEmpty(),
            play.volumeEnd?.toString().orEmpty(),
            play.volumeLow?.toString().orEmpty(),
            play.volumeHigh?.toString().orEmpty(),
            play.volumeMax?.toString().orEmpty(),
            play.volumeSource.orEmpty(),
            play.volumeChanges.toString(),
            mcu?.start?.toString().orEmpty(),
            mcu?.low?.toString().orEmpty(),
            mcu?.high?.toString().orEmpty(),
            mcu?.end?.toString().orEmpty(),
            mcu?.max?.toString().orEmpty(),
            mcu?.changes?.toString().orEmpty(),
            audio?.start?.toString().orEmpty(),
            audio?.low?.toString().orEmpty(),
            audio?.high?.toString().orEmpty(),
            audio?.end?.toString().orEmpty(),
            audio?.max?.toString().orEmpty(),
            audio?.changes?.toString().orEmpty(),
            play.seekBack.toString(),
            play.seekForward.toString(),
            play.deviceId,
            play.appVersion,
            play.open.toString(),
        )
    }

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}

private fun longOf(value: Any?): Long? = when (value) {
    is Long -> value
    is Int -> value.toLong()
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}

private fun intOf(value: Any?): Int? = when (value) {
    is Int -> value
    is Long -> value.toInt()
    is Number -> value.toInt()
    is String -> value.toIntOrNull()
    else -> null
}

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
