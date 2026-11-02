package com.example.carmusicmeter.data

import java.io.File

/**
 * Разбор состояния штатного плеера, у которого нет MediaSession.
 *
 * Android-зависимостей нет: парсер принимает тексты/узлы accessibility и
 * проверяется JVM-тестами. Для неоднозначного UI возвращается отказ, чтобы
 * сервис сохранил последний известный трек, а не создал фейковый.
 */
object StockPlayerParser {

    private val MEDIA_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "ape", "alac",
    )

    private val MEDIA_LINE = Regex(
        """^.+\.(mp3|flac|wav|m4a|aac|ogg|opus|wma|ape|alac)$""",
        RegexOption.IGNORE_CASE,
    )
    private val LIST_ROW = Regex("""^\d+\.\s+""")
    private val TIME = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")
    private val TIME_TOKEN = Regex("""(?<!\d)(\d{1,2}:\d{2}(?::\d{2})?)(?!\d)""")
    private val NUMBER = Regex("""^\d+([.,]\d+)?%?$""")
    private val PUNCTUATION = Regex("""^[\p{P}\p{S}\s]+$""")

    private val CONTROL_WORDS = setOf(
        "play", "pause", "stop", "next", "previous", "prev", "forward", "rewind",
        "shuffle", "repeat", "favorite", "favourite", "like", "add", "delete",
        "close", "cancel", "ok", "more", "menu", "back", "home", "search",
        "settings", "equalizer", "eq", "volume", "sound", "mute", "unmute",
        "folder", "folders", "album", "albums", "artist", "artists", "track",
        "tracks", "playlist", "playlists", "all", "favorites", "recent",
        "unknown", "воспроизвести", "приостановить", "пауза", "плей", "стоп",
        "следующий", "предыдущий", "перемотка", "вперед", "назад", "повтор",
        "перемешать", "избранное", "добавить", "удалить", "закрыть", "отмена",
        "меню", "назад", "домой", "поиск", "настройки", "эквалайзер", "звук",
        "громкость", "без звука", "включить звук", "папка", "папки", "альбом",
        "альбомы", "исполнитель", "исполнители", "трек", "треки", "плейлист",
        "плейлисты", "все", "недавние", "недавнее",
    )

    private val HEADERS = setOf(
        "список", "список музыки", "внутренняя память", "usb", "sd", "sd-карта",
        "флешка", "накопитель", "внутренний накопитель", "избранное", "папки",
        "альбомы", "исполнители", "плейлисты", "недавние", "все треки", "музыка",
    )

    private val SEPARATORS = listOf(" - ", " – ", " — ")

    data class AccessibilityParseResult(
        val state: StockPlayerState?,
        val accepted: Boolean,
        val rejectReason: String?,
        val confidence: String?,
        val allTexts: List<String>,
        val titleLine: String?,
    )

    fun fromBroadcast(
        extras: Map<String, Any?>,
        nowMs: Long,
        packageName: String,
        nowMonoMs: Long = nowMs,
        source: String = StockPlayerSources.FYT_BROADCAST,
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
            source = source,
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
    ): StockPlayerState? = inspectAccessibility(
        packageName = packageName,
        window = AccessibilityWindow(
            packageName = packageName,
            nodes = texts.map { AccessibilityNode(text = it) },
        ),
        playing = playing,
        nowMs = nowMs,
        nowMonoMs = nowMonoMs,
    ).state

    fun inspectAccessibility(
        packageName: String,
        window: AccessibilityWindow,
        playing: Boolean,
        nowMs: Long,
        nowMonoMs: Long = nowMs,
    ): AccessibilityParseResult {
        val allTexts = window.texts()
        if (allTexts.isEmpty()) {
            return AccessibilityParseResult(null, false, "empty", null, allTexts, null)
        }
        val timing = resolveTiming(window, allTexts)
        val strong = findStrongTrack(allTexts)
        if (strong != null) {
            return accepted(
                packageName, strong.first, strong.second, timing,
                allTexts, playing, nowMs, nowMonoMs, "high", strong.third,
            )
        }

        if (isHeader(allTexts.first())) {
            return AccessibilityParseResult(
                null, false, "folder_header", null, allTexts, allTexts.first(),
            )
        }
        if (allTexts.any(::isListRow)) {
            return AccessibilityParseResult(
                null, false, "list_row", null, allTexts, null,
            )
        }

        val titleOnly = findTitleOnly(allTexts)
        if (titleOnly != null) {
            return accepted(
                packageName, titleOnly.first, titleOnly.second, timing,
                allTexts, playing, nowMs, nowMonoMs, "medium", titleOnly.third,
            )
        }

        val candidates = candidates(allTexts)
        if (candidates.isEmpty()) {
            return AccessibilityParseResult(null, false, "no_track", null, allTexts, null)
        }
        val (artist, title) = titleAndArtist(candidates)
        if (title.isBlank()) {
            return AccessibilityParseResult(null, false, "no_track", null, allTexts, null)
        }
        return accepted(
            packageName, artist, title, timing,
            allTexts, playing, nowMs, nowMonoMs, "fallback", title,
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

    /** Оставляет только осмысленные строки для отображения и старых правил. */
    fun candidates(texts: List<String>): List<String> {
        val seen = HashSet<String>()
        return texts.asSequence()
            .mapNotNull(::normalize)
            .filter { it.length in 2..120 }
            .filterNot(::isControl)
            .filterNot(::isHeader)
            .filterNot(::isListRow)
            .filterNot { TIME.matches(it) || NUMBER.matches(it) || PUNCTUATION.matches(it) }
            .filter { seen.add(it) }
            .take(20)
            .toList()
    }

    /**
     * Строка «Исполнитель - Название.ext». Это самый надёжный признак
     * текущего трека в окне штатного плеера.
     */
    private fun findStrongTrack(texts: List<String>): Triple<String?, String, String>? {
        for (text in texts) {
            if (isListRow(text) || isHeader(text) || isControl(text) || TIME.matches(text)) {
                continue
            }
            val separator = SEPARATORS.firstOrNull { text.contains(it) } ?: continue
            val parts = text.split(separator, limit = 2).map { it.trim() }
            if (parts.size != 2 || parts.any { it.length <= 1 }) continue
            if (!isMediaLine(parts[1])) continue
            return Triple(parts[0], cleanTitle(parts[1]), text)
        }
        return null
    }

    /**
     * Строка «Название.ext» и следующая осмысленная строка как исполнитель.
     * Нужна для плееров, которые показывают название и исполнителя отдельно.
     */
    private fun findTitleOnly(texts: List<String>): Triple<String?, String, String>? {
        for (index in texts.indices) {
            val line = texts[index]
            if (isListRow(line) || isHeader(line) || isControl(line) || TIME.matches(line)) {
                continue
            }
            if (!isMediaLine(line)) continue
            val artist = texts.drop(index + 1).firstOrNull {
                !isListRow(it) && !isHeader(it) && !isControl(it) &&
                    !TIME.matches(it) && !NUMBER.matches(it) && it != line
            }
            if (artist != null) {
                return Triple(artist, cleanTitle(line), line)
            }
        }
        return null
    }

    private fun accepted(
        packageName: String,
        artist: String?,
        title: String,
        timing: Timing,
        allTexts: List<String>,
        playing: Boolean,
        nowMs: Long,
        nowMonoMs: Long,
        confidence: String,
        titleLine: String,
    ): AccessibilityParseResult {
        val state = StockPlayerState(
            packageName = packageName,
            title = title,
            artist = artist,
            durationMs = timing.durationMs,
            positionMs = timing.positionMs,
            playing = playing,
            playingKnown = true,
            source = StockPlayerSources.ACCESSIBILITY,
            updatedAtMs = nowMs,
            updatedAtMonoMs = nowMonoMs,
            rawTexts = candidates(allTexts),
        )
        return AccessibilityParseResult(
            state = state,
            accepted = true,
            rejectReason = null,
            confidence = confidence,
            allTexts = allTexts,
            titleLine = titleLine,
        )
    }

    private data class Timing(
        val positionMs: Long?,
        val durationMs: Long?,
    )

    private fun resolveTiming(window: AccessibilityWindow, texts: List<String>): Timing {
        val times = texts.asSequence()
            .flatMap { TIME_TOKEN.findAll(it).asSequence() }
            .mapNotNull { parseTime(it.value) }
            .distinct()
            .sorted()
            .toList()
        val range = window.ranges().firstOrNull {
            it.max > it.min && it.current >= it.min && it.current <= it.max
        }

        if (times.size >= 2) {
            return Timing(positionMs = times.first(), durationMs = times.last())
        }

        val single = times.singleOrNull()
        if (single != null) {
            val fraction = range?.takeIf { it.max <= 1.5f }?.let {
                ((it.current - it.min) / (it.max - it.min)).toDouble()
            }
            if (fraction != null && fraction > 0.02 && fraction < 0.98) {
                val duration = (single / fraction).toLong()
                if (duration > single && duration <= MAX_TRACK_MS) {
                    return Timing(positionMs = single, durationMs = duration)
                }
            }
            return Timing(positionMs = single, durationMs = null)
        }

        if (range != null && range.max > 60_000f) {
            return Timing(
                positionMs = range.current.toLong(),
                durationMs = range.max.toLong(),
            )
        }
        return Timing(positionMs = null, durationMs = null)
    }

    private fun parseTime(value: String): Long? {
        val parts = value.split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> ((parts[0] * 60 + parts[1]) * 1000L).takeIf { parts[1] < 60 }
            3 -> ((parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L)
                .takeIf { parts[1] < 60 && parts[2] < 60 }
            else -> null
        }
    }

    /**
     * Выбирает название и исполнителя. Строка «Artist - Title» имеет
     * приоритет; иначе первый осмысленный текст — название, следующий — артист.
     */
    fun titleAndArtist(candidates: List<String>): Pair<String?, String> {
        if (candidates.isEmpty()) return null to ""
        val first = candidates.first()
        val separator = SEPARATORS.firstOrNull { first.contains(it) }
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

    private fun isHeader(value: String): Boolean =
        value.lowercase() in HEADERS

    private fun isListRow(value: String): Boolean =
        LIST_ROW.containsMatchIn(value)

    private fun isMediaLine(value: String): Boolean =
        MEDIA_LINE.matches(value.trim())

    private fun isPlausibleTitle(value: String): Boolean =
        value.isNotBlank() && !value.equals("unknown", true) && !value.contains("Unknown")

    private fun cleanTitle(value: String): String {
        val trimmed = value.trim()
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

    private const val MAX_TRACK_MS = 6L * 60 * 60 * 1000
}

object StockPlayerSources {
    const val MEDIA_SESSION = "media_session"
    const val FYT_SERVICE = "fyt_service"
    const val FYT_BROADCAST = "fyt_broadcast"
    const val ACCESSIBILITY = "accessibility"
    const val NOTIFICATION = "notification"
}
