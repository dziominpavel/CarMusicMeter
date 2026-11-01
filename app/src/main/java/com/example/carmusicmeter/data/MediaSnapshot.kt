package com.example.carmusicmeter.data

enum class MediaPhase { PLAYING, PAUSED, STOPPED, UNKNOWN }

/**
 * Снимок одной активной медиасессии на момент опроса.
 * Содержит только те поля, которые нужны трекеру и экрану; полный дамп
 * (все ключи MediaMetadata, actions, состояние) собирается отдельно в пробе.
 */
data class MediaSnapshot(
    val packageName: String,
    val sessionTag: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long? = null,
    val phase: MediaPhase = MediaPhase.PLAYING,
    val speed: Float = 1f,
    val actions: Long = 0,
    val mediaId: String? = null,
    val metadataKeys: List<String> = emptyList(),
    val hasArtwork: Boolean = false,
    val source: String = StockPlayerSources.MEDIA_SESSION,
) {
    /**
     * Стабильный логический ключ трека.
     *
     * Не включает длительность, `mediaId`, путь и источник: они могут появиться
     * позже или смениться у того же трека. Ключ фиксируется при старте трека.
     */
    val trackKey: String
        get() = TrackIdentity.key(packageName, title, artist, sessionTag)

    fun sameTrack(other: MediaSnapshot): Boolean = TrackIdentity.sameTrack(this, other)

    val hasTitle: Boolean get() = !title.isNullOrBlank()
}

/** Живое состояние текущего трека для экрана и уведомления. */
data class LiveTrack(
    val key: String,
    val packageName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val positionMs: Long?,
    val phase: MediaPhase,
    val volume: Int,
    val volumeMax: Int,
    val startedAtMs: Long,
    val playedMs: Long,
    val mediaId: String?,
    val metadataKeys: List<String>,
    val source: String = StockPlayerSources.MEDIA_SESSION,
    val volumeSource: String = "audio",
)
