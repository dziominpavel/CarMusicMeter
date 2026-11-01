package com.example.carmusicmeter.data

import java.io.File

/**
 * Чистый разбор данных штатного плеера. Никакого Android, чтобы эвристики
 * можно было проверить JVM-тестами.
 */
object StockPlayerParser {

    private val MEDIA_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "ape", "alac",
    )

    private val CONTROL_WORDS = setOf(
        "play", "pause", "stop", "next", "previous", "prev", "forward", "rewind",
        "shuffle", "repeat", "favorite", "favourite", "like", "add", "delete",
        "close", "cancel", "ok", "more", "menu", "back", "home", "search",
        "settings", "equalizer", "eq", "volume", "sound", "mute", "unmute",
        "folder", "folders", "album", "albums", "artist", "artists", "track",
        "tracks", "playlist", "playlists", "all", "favorites", "recent",
        "unknown", "неизвестно", "исполнитель", "альбом", "папка", "папки",
        "плейлист", "все", "избранное", "поиск", "настройки", "меню", "назад",
        "играть", "пауза", "стоп", "следующий", "предыдущий", "громкость",
    )

    private val TIME = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")
    private val NUMBER = Regex("""^\d+([.,]\d+)?%?$""")
    private val PUNCTUATION = Regex("""^[\p{P}\p{S}\s]+$""")

    fun fromBroadcast(
        extras: Map<String, Any?>,
        nowMs: Long,
        packageName: String,
        nowMonoMs: Long = nowMs,
    ): StockPlayerState? {
        val title = string(extras[StockPlayerProtocol.EXTRA_TITLE])
            ?.takeIf(::isPlausibleTitle)
            ?: return null
        val artist = string(extras[StockPlayerProtocol.EXTRA_ARTIST])
            ?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
        val album = string(extras[StockPlayerProtocol.EXTRA_ALBUM])
            ?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
        val path = string(extras[StockPlayerProtocol.EXTRA_PATH])
            ?.takeIf { it.isNotBlank() }
        val playState = extras[StockPlayerProtocol.EXTRA_PLAYING]
        val playing = boolean(playState)
        val playingKnown = playState != null
        val duration = long(extras[StockPlayerProtocol.EXTRA_DURATION])?.takeIf { it > 0 }
        val position = long(extras[StockPlayerProtocol.EXTRA_POSITION])?.takeIf { it >= 0 }

        return StockPlayerState(
            packageName = packageName,
            title = title,
            artist = artist,
            album = album,
            path = path,
            durationMs = duration,
            positionMs = position,
            playing = playing,
            playingKnown = playingKnown,
            source = StockPlayerSources.FYT_BROADCAST,
            updatedAtMs = nowMs,
            updatedAtMonoMs = nowMonoMs,
            rawTexts = listOfNotNull(title, artist, album).distinct(),
            extras = extras.mapValues { (_, value) -> value?.toString() ?: "" },
        )
    }

    fun fromAccessibility(
        packageName: String,
        texts: List<String>,
        playing: Boolean,
        nowMs: Long,
        nowMonoMs: Long = nowMs,
    ): StockPlayerState? {
        val candidates = candidates(texts)
        if (candidates.isEmpty()) return null
        val (artist, title) = titleAndArtist(candidates)
        return StockPlayerState(
            packageName = packageName,
            title = title,
            artist = artist,
            playing = playing,
            playingKnown = true,
            source = StockPlayerSources.ACCESSIBILITY,
            updatedAtMs = nowMs,
            updatedAtMonoMs = nowMonoMs,
            rawTexts = candidates,
        )
    }

    fun fromNotification(
        packageName: String,
        title: String?,
        text: String?,
        remoteTexts: List<String>,
        nowMs: Long,
        nowMonoMs: Long = nowMs,
    ): StockPlayerState? {
        val candidates = candidates(listOfNotNull(title, text) + remoteTexts)
        if (candidates.isEmpty()) return null
        val (artist, resolvedTitle) = titleAndArtist(candidates)
        return StockPlayerState(
            packageName = packageName,
            title = resolvedTitle,
            artist = artist,
            playing = true,
            playingKnown = false,
            source = StockPlayerSources.NOTIFICATION,
            updatedAtMs = nowMs,
            updatedAtMonoMs = nowMonoMs,
            rawTexts = candidates,
        )
    }

    /** Отфильтрованные тексты в порядке появления, без дублей и служебных кнопок. */
    fun candidates(texts: List<String>): List<String> {
        val seen = HashSet<String>()
        return texts.asSequence()
            .mapNotNull(::normalize)
            .filter { it.length in 2..120 }
            .filterNot(::isControl)
            .filterNot { TIME.matches(it) || NUMBER.matches(it) || PUNCTUATION.matches(it) }
            .filter { seen.add(it) }
            .take(20)
            .toList()
    }

    /**
     * Возвращает пару (исполнитель, название). Для строки «Artist - Title»
     * название берётся справа: так чаще показывают штатные плееры.
     */
    fun titleAndArtist(candidates: List<String>): Pair<String?, String> {
        if (candidates.isEmpty()) return null to ""
        val first = candidates.first()
        val separator = listOf(" - ", " — ", " – ").firstOrNull { first.contains(it) }
        if (separator != null) {
            val parts = first.split(separator, limit = 2).map { it.trim() }
            if (parts.size == 2 && parts.all { it.length > 1 }) {
                return parts[0] to cleanTitle(parts[1])
            }
        }
        val title = cleanTitle(first)
        val artist = candidates.drop(1).firstOrNull { it != first && it.length > 1 }
        return artist to title
    }

    private fun normalize(value: String?): String? =
        value?.trim()?.replace(Regex("""\s+"""), " ")?.takeIf { it.isNotEmpty() }

    private fun isControl(value: String): Boolean =
        value.lowercase() in CONTROL_WORDS

    private fun isPlausibleTitle(value: String): Boolean =
        value.isNotBlank() && !value.equals("unknown", true) && !value.contains("Unknown")

    private fun cleanTitle(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.contains('/') && !trimmed.contains('\\')) return trimmed
        val name = File(trimmed).nameWithoutExtension
        val extension = File(trimmed).extension.lowercase()
        return if (extension in MEDIA_EXTENSIONS && name.isNotBlank()) name else trimmed
    }

    private fun string(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is CharSequence -> value.toString()
        else -> value.toString()
    }

    private fun boolean(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", true) || value == "1"
        else -> false
    }

    private fun long(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}

object StockPlayerSources {
    const val MEDIA_SESSION = "media_session"
    const val FYT_BROADCAST = "fyt_broadcast"
    const val ACCESSIBILITY = "accessibility"
    const val NOTIFICATION = "notification"
}
