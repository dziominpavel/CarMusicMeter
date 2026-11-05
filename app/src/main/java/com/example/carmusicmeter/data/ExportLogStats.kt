package com.example.carmusicmeter.data

import java.io.File

/** Статистика одного журнала, собранная при копировании в ZIP. */
data class ExportLogStats(
    val path: String,
    val events: Int = 0,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    /** Сколько событий записано при непроверенных часах. */
    val unverified: Int = 0,
    val clockChanged: Boolean = false,
) {
    /** Журнал целиком записан при непроверенных часах. */
    val fullyUnverified: Boolean get() = events > 0 && unverified >= events
}

/**
 * Разбор журнала построчно для `manifest.json`.
 *
 * Полный `JSONObject` на каждую строку на магнитоле слишком дорог, поэтому
 * берутся только `ts`, признак `clockVerified` и наличие `clock_changed`:
 * по ним считается фактическое покрытие выгрузки.
 */
object ExportLogScanner {

    private const val TS_KEY = "\"ts\":"
    private const val UNVERIFIED_FLAG = "\"clockVerified\":false"
    private const val CLOCK_CHANGED = "\"type\":\"clock_changed\""

    fun scanFile(path: String, file: File): ExportLogStats = runCatching {
        file.useLines { lines -> scan(path, lines) }
    }.getOrElse { ExportLogStats(path = path) }

    fun scan(path: String, lines: Sequence<String>): ExportLogStats {
        var events = 0
        var unverified = 0
        var clockChanged = false
        var minTs = Long.MAX_VALUE
        var maxTs = Long.MIN_VALUE

        lines.forEach { line ->
            if (line.isBlank()) return@forEach
            events += 1
            if (line.contains(UNVERIFIED_FLAG)) unverified += 1
            if (line.contains(CLOCK_CHANGED)) clockChanged = true
            val ts = lineTs(line) ?: return@forEach
            if (ts < minTs) minTs = ts
            if (ts > maxTs) maxTs = ts
        }

        return ExportLogStats(
            path = path,
            events = events,
            fromMs = minTs.takeIf { it != Long.MAX_VALUE },
            toMs = maxTs.takeIf { it != Long.MIN_VALUE },
            unverified = unverified,
            clockChanged = clockChanged,
        )
    }

    /** `ts` всегда первое поле события, поэтому достаточно поиска цифр после ключа. */
    private fun lineTs(line: String): Long? {
        val key = line.indexOf(TS_KEY)
        if (key < 0) return null
        var index = key + TS_KEY.length
        while (index < line.length && line[index] == ' ') index += 1
        val start = index
        while (index < line.length && line[index].isDigit()) index += 1
        if (index == start) return null
        return line.substring(start, index).toLongOrNull()
    }
}

/**
 * Фактическое покрытие выгрузки: по временам событий, а не по датам в именах
 * файлов. Журнал непроверенного времени не участвует — его `ts` недостоверны,
 * и отчёт восстанавливает их по `clock_changed` того же `bootId`.
 */
fun exportCoverage(
    stats: List<ExportLogStats>,
    fallback: ExportCoverage,
): ExportCoverage {
    val dated = stats.filter { !it.path.endsWith(EventLog.UNVERIFIED_LOG_NAME) }
    val from = dated.mapNotNull { it.fromMs }.minOrNull()
    val to = dated.mapNotNull { it.toMs }.maxOrNull()
    if (from == null || to == null) return fallback
    return ExportCoverage(fromMs = from, toMs = to)
}
