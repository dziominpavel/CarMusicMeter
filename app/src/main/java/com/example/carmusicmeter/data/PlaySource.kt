package com.example.carmusicmeter.data

import java.io.File

/**
 * Объединяет live-факты, историю из кэша и открытое прослушивание.
 *
 * Чистый слой: знает только файлы и параметры, без Android. Это позволяет
 * проверить слияние, watermark и дедупликацию JVM-тестами.
 */
class PlaySource(
    private val baseDir: File,
    private val deviceId: String,
    private val appVersion: String,
    private val liveSince: Long?,
    private val nowMs: Long = System.currentTimeMillis(),
) {

    private val playsDir = File(baseDir, "plays")
    private val logsDir = File(baseDir, "logs")
    private val cache = PlayCache(baseDir)
    private var last: PlayFactsDiagnostics? = null

    fun load(
        fromMs: Long? = null,
        toMs: Long? = null,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): List<PlayRecord> {
        var errors = 0
        val liveRaw = loadLive { errors += it }
        val live = liveRaw
            .groupBy { it.playId }
            .mapValues { (_, items) ->
                items.firstOrNull { !it.open } ?: items.maxByOrNull { it.endedAt }!!
            }
            .values

        val history = ArrayList<PlayRecord>()
        val logFiles = logsDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("events-") && it.name.endsWith(".jsonl") }
            ?.sortedBy { it.name }
            .orEmpty()
        onProgress?.invoke(0, logFiles.size)
        var done = 0
        for (file in logFiles) {
            val dayDate = StorageDates.logDate(file.name)
            if (dayDate != null && fromMs != null &&
                StorageDates.endOfDayMs(dayDate) < fromMs
            ) {
                done += 1
                onProgress?.invoke(done, logFiles.size)
                continue
            }
            val day = dayDate?.toString() ?: "unverified"
            val cached = cache.load(day, file)
            val built = if (cached != null) {
                cached
            } else {
                val result = file.useLines { lines ->
                    PlayBuilder.build(
                        lines = lines,
                        deviceId = deviceId,
                        appVersion = appVersion,
                        nowMs = nowMs,
                    )
                }
                errors += result.malformed
                cache.store(day, file, result.plays)
                result.plays
            }
            history += built
            done += 1
            onProgress?.invoke(done, logFiles.size)
        }

        var historyKept = if (liveSince != null) {
            history.filter { it.startedAt < liveSince }
        } else {
            history
        }
        val beforeOverlap = historyKept.size
        historyKept = historyKept.filterNot { candidate ->
            live.any { fact ->
                fact.trackKey == candidate.trackKey && overlaps(fact, candidate)
            }
        }
        val overlaps = beforeOverlap - historyKept.size

        val open = readOpen()
        val merged = (live + historyKept + listOfNotNull(open))
            .distinctBy { it.playId }
            .filter { play ->
                play.open || (
                    (fromMs == null || play.startedAt >= fromMs) &&
                        (toMs == null || play.startedAt <= toMs)
                    )
            }
            .sortedWith(compareBy<PlayRecord> { it.startedAt }.thenBy { it.playId })

        last = PlayFactsDiagnostics(
            live = live.size,
            history = historyKept.size,
            open = if (open != null) 1 else 0,
            cacheDays = cache.listDays().size,
            builderVersion = PlayBuilder.VERSION,
            overlaps = overlaps,
            errors = errors,
        )
        return merged
    }

    fun diagnostics(): PlayFactsDiagnostics = last ?: scanDiagnostics()

    /** Делит факты на подтверждённые и с непроверенной датой. */
    fun splitDated(plays: List<PlayRecord>): Pair<List<PlayRecord>, List<PlayRecord>> =
        plays.partition { it.clockVerified }

    /** Число событий `capture_gap` за период: качество данных в отчёте. */
    fun captureGaps(fromMs: Long? = null, toMs: Long? = null): Int {
        var count = 0
        logsDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("events-") && it.name.endsWith(".jsonl") }
            ?.forEach { file ->
                val date = StorageDates.logDate(file.name)
                if (date != null && fromMs != null && StorageDates.endOfDayMs(date) < fromMs) {
                    return@forEach
                }
                file.useLines { lines ->
                    lines.forEach { line ->
                        if (line.contains("\"type\":\"capture_gap\"")) count += 1
                    }
                }
            }
        return count
    }

    private fun scanDiagnostics(): PlayFactsDiagnostics {
        val live = playsDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("plays-") && it.name.endsWith(".jsonl") }
            ?.sumOf { file -> file.useLines { lines -> lines.count { it.isNotBlank() } } }
            ?: 0
        val history = cache.listDays().sumOf { day ->
            val file = cache.file(day)
            if (!file.isFile) 0 else {
                val lines = file.useLines { it.count { line -> line.isNotBlank() } }
                (lines - 1).coerceAtLeast(0)
            }
        }
        return PlayFactsDiagnostics(
            live = live,
            history = history,
            open = if (File(playsDir, EventLog.OPEN_PLAY_NAME).isFile) 1 else 0,
            cacheDays = cache.listDays().size,
            builderVersion = PlayBuilder.VERSION,
            overlaps = last?.overlaps ?: 0,
            errors = last?.errors ?: 0,
        )
    }

    private fun loadLive(errors: (Int) -> Unit): List<PlayRecord> {
        val result = ArrayList<PlayRecord>()
        playsDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("plays-") && it.name.endsWith(".jsonl") }
            ?.forEach { file ->
                file.useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank()) return@forEach
                        val play = PlayRecord.fromJson(line)
                        if (play == null) errors(1) else result += play
                    }
                }
            }
        return result
    }

    private fun readOpen(): PlayRecord? {
        val file = File(playsDir, EventLog.OPEN_PLAY_NAME)
        if (!file.isFile) return null
        return runCatching { PlayRecord.fromJson(file.readText()) }
            .getOrNull()
            ?.takeIf { it.open }
    }

    private fun overlaps(a: PlayRecord, b: PlayRecord): Boolean =
        a.startedAt <= b.endedAt && b.startedAt <= a.endedAt
}
