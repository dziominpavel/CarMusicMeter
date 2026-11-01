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
    val playingKnown: Boolean = true,
    val source: String,
    val updatedAtMs: Long,
    val updatedAtMonoMs: Long = updatedAtMs,
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

    fun toSnapshot(nowMonoMs: Long, isMusicActive: Boolean): MediaSnapshot? {
        val effectivePlaying = when {
            source == StockPlayerSources.ACCESSIBILITY -> playing || isMusicActive
            source == StockPlayerSources.NOTIFICATION -> isMusicActive
            source == StockPlayerSources.FYT_BROADCAST && !playingKnown -> isMusicActive
            else -> playing
        }
        val age = (nowMonoMs - updatedAtMonoMs).coerceAtLeast(0L)
        val staleAfter = when {
            !effectivePlaying -> PAUSED_STALE_MS
            source == StockPlayerSources.ACCESSIBILITY -> ACCESSIBILITY_STALE_MS
            else -> PLAYING_STALE_MS
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
        const val PLAYING_STALE_MS = 30 * 60_000L
        const val ACCESSIBILITY_STALE_MS = 90_000L
        const val PAUSED_STALE_MS = 10 * 60_000L
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

    fun snapshot(nowMonoMs: Long, isMusicActive: Boolean): MediaSnapshot? =
        current?.toSnapshot(nowMonoMs, isMusicActive)

    /** Последнее состояние, если оно ещё не устарело; иначе null. */
    fun activeState(nowMonoMs: Long, isMusicActive: Boolean): StockPlayerState? =
        current?.takeIf { it.toSnapshot(nowMonoMs, isMusicActive) != null }

    /** @return true, если изменились название/исполнитель/состояние. */
    @Synchronized
    fun update(state: StockPlayerState): Boolean {
        val old = current
        if (old != null && old.source == StockPlayerSources.FYT_BROADCAST &&
            state.source != StockPlayerSources.FYT_BROADCAST &&
            state.updatedAtMonoMs - old.updatedAtMonoMs < BROADCAST_GRACE_MS
        ) {
            return false
        }
        if (old != null && old.source == StockPlayerSources.ACCESSIBILITY &&
            state.source == StockPlayerSources.NOTIFICATION &&
            state.updatedAtMonoMs - old.updatedAtMonoMs < ACCESSIBILITY_GRACE_MS
        ) {
            return false
        }
        current = state
        return old?.metaSignature != state.metaSignature
    }

    /**
     * Поддерживает состояние свежим, пока окно плеера видимо, даже если текст
     * не менялся. Иначе длинный трек истекал бы по таймауту.
     */
    @Synchronized
    fun touch(nowMonoMs: Long): Boolean {
        val old = current ?: return false
        current = old.copy(updatedAtMonoMs = nowMonoMs)
        return true
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
