package com.example.carmusicmeter.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Последнее состояние сервиса metadata FYT.
 *
 * Нужно для снимка и для события `fyt_metadata_service`: событие пишется при
 * первом получении и при смене набора extras, а не на каждом обновлении.
 */
object FytMetadataStatus {

    private val lock = Any()

    @Volatile
    var received: Boolean = false
        private set

    @Volatile
    var packageName: String? = null
        private set

    @Volatile
    var keys: Set<String> = emptySet()
        private set

    @Volatile
    var updatedAtMs: Long? = null
        private set

    @Volatile
    var title: String? = null
        private set

    @Volatile
    var artist: String? = null
        private set

    @Volatile
    var playing: Boolean? = null
        private set

    /** @return true, если это первый пакет или изменился набор ключей. */
    fun update(
        packageName: String,
        keys: Set<String>,
        title: String?,
        artist: String?,
        playing: Boolean?,
        nowMs: Long,
    ): Boolean = synchronized(lock) {
        val changed = !received || keys != this.keys
        received = true
        this.packageName = packageName
        this.keys = keys
        this.updatedAtMs = nowMs
        this.title = title
        this.artist = artist
        this.playing = playing
        changed
    }

    fun toLogEvent(nowMs: Long): LogEvent = LogEvent(
        ts = nowMs,
        type = "fyt_metadata_service",
        fields = linkedMapOf(
            "received" to true,
            "package" to packageName,
            "keys" to keys.sorted(),
            "title" to title,
            "artist" to artist,
            "playing" to playing,
        ),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("received", received)
        put("package", Json.toJsonValue(packageName))
        put("keys", JSONArray(keys.sorted()))
        put("updatedAt", Json.toJsonValue(updatedAtMs))
        put("title", Json.toJsonValue(title))
        put("artist", Json.toJsonValue(artist))
        put("playing", Json.toJsonValue(playing))
    }

    fun reset() {
        synchronized(lock) {
            received = false
            packageName = null
            keys = emptySet()
            updatedAtMs = null
            title = null
            artist = null
            playing = null
        }
    }
}
