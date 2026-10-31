package com.example.carmusicmeter.data

/** Последнее состояние штатного плеера, полученное не из MediaSession. */
data class StockPlayerState(
    val packageName: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val path: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long? = null,
    val playing: Boolean = false,
    val source: String,
    val updatedAtMs: Long,
    val rawTexts: List<String> = emptyList(),
    val extras: Map<String, String> = emptyMap(),
) {
    val metaSignature: String
        get() = listOf(
            source,
            packageName,
            title,
            artist,
            album,
            path,
            playing,
        ).joinToString("|")

    fun toSnapshot(nowMs: Long, isMusicActive: Boolean): MediaSnapshot? {
        val effectivePlaying = playing ||
            (source == StockPlayerSources.ACCESSIBILITY && isMusicActive)
        val age = nowMs - updatedAtMs
        val staleAfter = when {
            !effectivePlaying -> PAUSED_STALE_MS
            source == StockPlayerSources.FYT_BROADCAST -> BROADCAST_STALE_MS
            source == StockPlayerSources.NOTIFICATION -> BROADCAST_STALE_MS
            else -> ACCESSIBILITY_STALE_MS
        }
        if (age > staleAfter) return null
        if (title.isBlank()) return null

        return MediaSnapshot(
            packageName = packageName,
            sessionTag = "stock",
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            positionMs = positionMs,
            phase = if (effectivePlaying) MediaPhase.PLAYING else MediaPhase.PAUSED,
            speed = 1f,
            actions = 0L,
            mediaId = path,
            metadataKeys = when (source) {
                StockPlayerSources.FYT_BROADCAST -> listOf(
                    "fyt.title", "fyt.artist", "fyt.album", "fyt.path", "fyt.state",
                )
                StockPlayerSources.ACCESSIBILITY -> listOf("accessibility.text")
                else -> listOf("notification.text")
            },
            hasArtwork = false,
            source = source,
        )
    }

    private companion object {
        const val BROADCAST_STALE_MS = 30_000L
        const val ACCESSIBILITY_STALE_MS = 10 * 60_000L
        const val PAUSED_STALE_MS = 15_000L
    }
}

/**
 * Мост между источниками штатного плеера и службой наблюдения.
 *
 * Объект живёт в процессе приложения: broadcast приходит в динамический
 * приёмник службы, accessibility — в отдельный сервис. Служба раз в тик
 * забирает последний снимок и отдаёт его трекеру.
 */
object StockPlayerBridge {

    private const val BROADCAST_GRACE_MS = 10_000L
    private const val ACCESSIBILITY_GRACE_MS = 3_000L

    @Volatile
    private var current: StockPlayerState? = null

    @Synchronized
    fun latest(): StockPlayerState? = current

    fun snapshot(nowMs: Long, isMusicActive: Boolean): MediaSnapshot? =
        current?.toSnapshot(nowMs, isMusicActive)

    /** Последнее состояние, если оно ещё не устарело; иначе null. */
    fun activeState(nowMs: Long, isMusicActive: Boolean): StockPlayerState? =
        current?.takeIf { it.toSnapshot(nowMs, isMusicActive) != null }

    /** @return true, если изменились название/исполнитель/состояние. */
    @Synchronized
    fun update(state: StockPlayerState): Boolean {
        val old = current
        if (old != null && old.source == StockPlayerSources.FYT_BROADCAST &&
            state.source != StockPlayerSources.FYT_BROADCAST &&
            state.updatedAtMs - old.updatedAtMs < BROADCAST_GRACE_MS
        ) {
            return false
        }
        if (old != null && old.source == StockPlayerSources.ACCESSIBILITY &&
            state.source == StockPlayerSources.NOTIFICATION &&
            state.updatedAtMs - old.updatedAtMs < ACCESSIBILITY_GRACE_MS
        ) {
            return false
        }
        current = state
        return old?.metaSignature != state.metaSignature
    }

    @Synchronized
    fun remove(): Boolean {
        val hadState = current != null
        current = null
        return hadState
    }

    @Synchronized
    fun reset() {
        current = null
    }
}
