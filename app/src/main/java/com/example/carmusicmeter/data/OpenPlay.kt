package com.example.carmusicmeter.data

import org.json.JSONObject

/** Статистика громкости одного источника в сохранённом прослушивании. */
data class OpenPlayVolume(
    val start: Int,
    val low: Int,
    val high: Int,
    val end: Int,
    val max: Int,
    val changes: Int,
)

/**
 * Прослушивание, открытое на момент последней контрольной точки.
 *
 * Сохраняется синхронно в настройки: если питание пропадёт или пакет заменят,
 * следующая служба закроет это прослушивание одним `track_ended`, а не начнёт
 * второе для того же трека. Поля повторяют `track_ended`, чтобы восстановленное
 * окончание не теряло длительность, путь и статистику громкости.
 */
data class OpenPlay(
    val bootId: String,
    val trackKey: String,
    val packageName: String,
    val source: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val mediaId: String?,
    val durationMs: Long?,
    val positionMs: Long?,
    val playedMs: Long,
    val startedAtWallMs: Long,
    val lastTsWallMs: Long,
    val volumeStart: Int,
    val volumeLow: Int,
    val volumeHigh: Int,
    val volumeEnd: Int,
    val volumeMax: Int,
    val volumeChanges: Int,
    val volumeSource: String,
    val volumeStats: Map<String, OpenPlayVolume> = emptyMap(),
) {
    fun toJson(): String = JSONObject().apply {
        put("bootId", bootId)
        put("trackKey", trackKey)
        put("package", packageName)
        put("source", source)
        put("title", title ?: JSONObject.NULL)
        put("artist", artist ?: JSONObject.NULL)
        put("album", album ?: JSONObject.NULL)
        put("mediaId", mediaId ?: JSONObject.NULL)
        put("durationMs", durationMs ?: JSONObject.NULL)
        put("positionMs", positionMs ?: JSONObject.NULL)
        put("playedMs", playedMs)
        put("startedAt", startedAtWallMs)
        put("lastTs", lastTsWallMs)
        put("volumeStart", volumeStart)
        put("volumeLow", volumeLow)
        put("volumeHigh", volumeHigh)
        put("volumeEnd", volumeEnd)
        put("volumeMax", volumeMax)
        put("volumeChanges", volumeChanges)
        put("volumeSource", volumeSource)
        put(
            "volumeStats",
            JSONObject().apply {
                volumeStats.forEach { (source, value) ->
                    put(
                        source,
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
    }.toString()

    companion object {

        fun fromJson(raw: String?): OpenPlay? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                OpenPlay(
                    bootId = json.getString("bootId"),
                    trackKey = json.getString("trackKey"),
                    packageName = json.optStringOrNull("package").orEmpty(),
                    source = json.optStringOrNull("source").orEmpty(),
                    title = json.optStringOrNull("title"),
                    artist = json.optStringOrNull("artist"),
                    album = json.optStringOrNull("album"),
                    mediaId = json.optStringOrNull("mediaId"),
                    durationMs = json.optLongOrNull("durationMs"),
                    positionMs = json.optLongOrNull("positionMs"),
                    playedMs = json.optLong("playedMs", 0L),
                    startedAtWallMs = json.optLong("startedAt", 0L),
                    lastTsWallMs = json.optLong("lastTs", 0L),
                    volumeStart = json.optInt("volumeStart", 0),
                    volumeLow = json.optInt("volumeLow", 0),
                    volumeHigh = json.optInt("volumeHigh", 0),
                    volumeEnd = json.optInt("volumeEnd", 0),
                    volumeMax = json.optInt("volumeMax", 0),
                    volumeChanges = json.optInt("volumeChanges", 0),
                    volumeSource = json.optStringOrNull("volumeSource").orEmpty(),
                    volumeStats = json.optJSONObject("volumeStats")?.let { stats ->
                        stats.keys().asSequence().associateWith { key ->
                            val item = stats.getJSONObject(key)
                            OpenPlayVolume(
                                start = item.optInt("start", 0),
                                low = item.optInt("low", 0),
                                high = item.optInt("high", 0),
                                end = item.optInt("end", 0),
                                max = item.optInt("max", 0),
                                changes = item.optInt("changes", 0),
                            )
                        }
                    }.orEmpty(),
                )
            }.getOrNull()
        }
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
