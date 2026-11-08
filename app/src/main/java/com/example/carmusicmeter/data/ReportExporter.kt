package com.example.carmusicmeter.data

import com.example.carmusicmeter.data.metrics.ReportModel
import com.example.carmusicmeter.data.metrics.TrackMetrics
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Три производных артефакта отчёта: таблица, автономный HTML и промпт для ИИ.
 * Всё считается из одной [ReportModel], поэтому числа не расходятся.
 */
object ReportExporter {

    data class Bundle(
        val generated: List<ExportGenerated>,
        val stats: ExportReportStats,
    )

    fun build(model: ReportModel): Bundle {
        val stats = ExportReportStats(
            period = model.period.tag,
            days = model.period.days,
            tracks = model.tracks.size,
            plays = model.summary.plays,
            generatedAtMs = model.generatedAt,
            scoredTracks = model.tracks.count { it.affinity != null },
            volumeCoverage = model.quality.mcuCoverage,
        )
        return Bundle(
            generated = listOf(
                ExportGenerated("tracks.csv", csv(model).toByteArray(Charsets.UTF_8)),
                ExportGenerated("report.html", html(model).toByteArray(Charsets.UTF_8)),
                ExportGenerated("ai-prompt.md", prompt(model).toByteArray(Charsets.UTF_8)),
            ),
            stats = stats,
        )
    }

    private val CSV_HEADER = listOf(
        "trackKey", "title", "artist", "package", "source",
        "plays", "rated_plays", "completed", "skipped", "interrupted",
        "censored", "not_played", "unknown", "played_ms",
        "completion", "listened", "skip_rate", "early_skip_rate", "avg_skip_pct",
        "duration_ms", "volume_delta", "volume_known", "volume_unknown",
        "volume_changes", "seek_back", "seek_fwd", "days", "last_played",
        "affinity", "affinity_confidence", "affinity_plays", "affinity_approx",
        "affinity_reasons",
    )

    fun csv(model: ReportModel): String = buildString {
        append("\uFEFF")
        append(CSV_HEADER.joinToString(","))
        append("\r\n")
        model.tracks.forEach { track ->
            val row = listOf(
                track.trackKey, track.title, track.artist.orEmpty(), track.packageName, track.source,
                track.plays.toString(), track.ratedPlays.toString(), track.completed.toString(),
                track.skipped.toString(), track.interrupted.toString(), track.censored.toString(),
                track.notPlayed.toString(), track.unknown.toString(), track.playedMs.toString(),
                track.completion?.toString().orEmpty(), track.listened?.toString().orEmpty(),
                track.skipRate?.toString().orEmpty(), track.earlySkipRate?.toString().orEmpty(),
                track.avgSkipPct?.toString().orEmpty(), track.durationMs?.toString().orEmpty(),
                track.volumeDelta?.toString().orEmpty(), track.volumeKnown.toString(),
                track.volumeUnknown.toString(), track.volumeChanges.toString(),
                track.seekBack.toString(), track.seekForward.toString(), track.days.toString(),
                track.lastPlayedAt?.toString().orEmpty(),
                track.affinity?.toString().orEmpty(),
                if (track.affinity == null) "" else String.format(Locale.US, "%.2f", track.affinityConfidence),
                track.affinityPlays.toString(),
                track.affinityApprox.toString(),
                track.affinityReasons.joinToString("; "),
            )
            append(row.joinToString(",") { escapeCsv(it) })
            append("\r\n")
        }
    }

    fun html(model: ReportModel): String = buildString {
        append("<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<title>CarMusicMeter — отчёт</title><style>")
        append(
            """
            :root{color-scheme:light}*{box-sizing:border-box}
            body{margin:0;background:#f4f6f8;color:#17202a;font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
            main{max-width:1100px;margin:0 auto;padding:28px 20px 48px}
            h1{font-size:26px;margin:0 0 4px}h2{font-size:19px;margin:28px 0 10px}
            .muted{color:#65717b}.small{font-size:13px}
            .cards{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin:18px 0}
            .card{background:#fff;border:1px solid #e2e7ec;border-radius:14px;padding:14px}
            .card .value{font-size:26px;font-weight:700}.card .label{font-size:13px;color:#65717b}
            .quality{background:#fff;border:1px solid #e2e7ec;border-radius:14px;padding:14px}
            table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e2e7ec;border-radius:14px;overflow:hidden}
            th,td{padding:8px 10px;border-bottom:1px solid #eef1f4;text-align:left;vertical-align:top}
            th{background:#f8fafb;font-size:13px;color:#52606b}
            tr:last-child td{border-bottom:0}
            .num{text-align:right;white-space:nowrap}
            .tag{display:inline-block;padding:1px 7px;border-radius:999px;background:#eef4ff;color:#244b8a;font-size:12px}
            .warn{color:#9a5b00}.bad{color:#a12b2b}.good{color:#1f6b3a}
            footer{margin-top:28px;color:#65717b;font-size:13px}
            @media (max-width:760px){.cards{grid-template-columns:repeat(2,1fr)}}
            """.trimIndent(),
        )
        append("</style></head><body><main>")
        append("<h1>CarMusicMeter — отчёт</h1>")
        append("<div class=\"muted\">Период: ").append(escapeHtml(periodLabel(model))).append(" · собран ")
            .append(date(model.generatedAt)).append("</div>")

        append("<div class=\"cards\">")
        card("Прослушано", duration(model.summary.playedMs))
        card("Прослушиваний", model.summary.plays.toString())
        card("Поездок", model.summary.trips.toString())
        card("Треков", model.summary.tracks.toString())
        append("</div>")

        append("<div class=\"quality\"><b>Данные:</b> ")
        append("длительность ").append(pct(model.quality.durationCoverage))
        append(" · позиция ").append(pct(model.quality.positionCoverage))
        append(" · громкость MCU ").append(pct(model.quality.mcuCoverage))
        append(" · разрывов ").append(model.quality.captureGaps)
        if (model.quality.open > 0) append(" · открытых ").append(model.quality.open)
        if (model.quality.restored > 0) append(" · восстановленных ").append(model.quality.restored)
        if (model.quality.unverified > 0) append(" · непроверенных ").append(model.quality.unverified)
        append("<div class=\"small muted\">Прочерк — данных нет; это не ноль.</div>")
        append("<div class=\"small muted\">Оценка — v1: громкость важнее времени, прочерк — нет данных")
        if (!model.affinityCalibrated) append("; калибровка черновая, данных мало")
        append(".</div></div>")

        if (model.openPlay != null) {
            append("<p class=\"small muted\">Сейчас играет: ")
                .append(escapeHtml(model.openPlay.title.orEmpty()))
                .append(" — ").append(escapeHtml(model.openPlay.artist.orEmpty()))
                .append("; в итоги не входит.</p>")
        }

        section("Слушал чаще", model.top, "plays")
        section("Пропускал чаще", model.antiTop, "skip")
        section("Кандидаты «послушать ещё»", model.candidates, "candidate")

        append("<h2>Все треки</h2>")
        append("<table><thead><tr><th>Трек</th><th class=\"num\">Play</th>")
        append("<th class=\"num\">Оценка v1</th>")
        append("<th class=\"num\">Completion</th><th class=\"num\">Скип</th>")
        append("<th class=\"num\">Громкость</th><th class=\"num\">Последний</th></tr></thead><tbody>")
        model.tracks.forEach { track ->
            append("<tr><td><b>").append(escapeHtml(track.displayTitle)).append("</b>")
            track.artist?.takeIf { it.isNotBlank() }?.let {
                append("<div class=\"small muted\">").append(escapeHtml(it)).append("</div>")
            }
            append("</td>")
            append("<td class=\"num\">").append(track.plays).append("</td>")
            append("<td class=\"num\">").append(track.affinity?.toString() ?: "—").append("</td>")
            append("<td class=\"num\">").append(pct(track.completion)).append("</td>")
            append("<td class=\"num\">").append(pct(track.skipRate)).append("</td>")
            append("<td class=\"num\">").append(volume(track.volumeDelta)).append("</td>")
            append("<td class=\"num\">").append(track.lastPlayedAt?.let(::date) ?: "—").append("</td></tr>")
        }
        append("</tbody></table>")

        append("<h2>Как читать</h2><div class=\"quality small\">")
        append("<b>Play</b> — прослушивание; <b>completion</b> — до какого места дошёл; ")
        append("<b>скип</b> — трек сменили до конца; <b>неизвестно</b> — плеер не отдал данные. ")
        append("Технические окончания (потеря сессии, остановка службы) не считаются скипами.")
        append("</div>")
        append("<footer>Данные собраны приложением на устройстве, без сети. ")
        append("Для анализа с ИИ используйте <code>ai-prompt.md</code>.</footer>")
        append("</main></body></html>")
    }

    private fun StringBuilder.card(label: String, value: String) {
        append("<div class=\"card\"><div class=\"value\">").append(escapeHtml(value))
            .append("</div><div class=\"label\">").append(escapeHtml(label)).append("</div></div>")
    }

    private fun StringBuilder.section(title: String, tracks: List<TrackMetrics>, mode: String) {
        if (tracks.isEmpty()) return
        append("<h2>").append(escapeHtml(title)).append("</h2>")
        append("<table><thead><tr><th>Трек</th><th class=\"num\">Показатель</th>")
        append("<th>Почему</th></tr></thead><tbody>")
        tracks.forEach { track ->
            val metric = when (mode) {
                "plays" -> track.plays.toString() + " play"
                "skip" -> pct(track.skipRate) + " скипов"
                else -> pct(track.skipRate)
            }
            append("<tr><td><b>").append(escapeHtml(track.displayTitle)).append("</b>")
            track.artist?.takeIf { it.isNotBlank() }?.let {
                append("<div class=\"small muted\">").append(escapeHtml(it)).append("</div>")
            }
            append("</td><td class=\"num\">").append(metric).append("</td>")
            append("<td class=\"small muted\">")
                .append(escapeHtml(if (mode == "candidate") track.antiTopReason.orEmpty() else ""))
                .append("</td></tr>")
        }
        append("</tbody></table>")
    }

    fun prompt(model: ReportModel): String = buildString {
        append("# CarMusicMeter — данные для анализа\n\n")
        append("Ты аналитик музыки. Ниже — отчёт о прослушиваниях в автомобиле за ")
        append(periodLabel(model)).append(".\n\n")
        append("## Правила\n\n")
        append("- Используй только числа из этого файла; не выдумывай статистику.\n")
        append("- Если данных мало, скажи это прямо.\n")
        append("- not_played и unknown — технические окончания, не скипы.\n")
        append("- Громкость — главный сигнал симпатии: подъём важнее понижения, понижение на стоянке не в счёт.\n")
        append("- Оценка трека — v1 (0–100, громкость и время); прочерк — нет данных.\n")
        append("- Пиши по-русски, 300–600 слов.\n\n")
        append("## Сводка\n\n")
        append("- Прослушано: ").append(duration(model.summary.playedMs)).append("\n")
        append("- Прослушиваний: ").append(model.summary.plays)
        append(" (оценённых ").append(model.summary.rated)
        append(", технических ").append(model.summary.censored).append(")\n")
        append("- Поездок: ").append(model.summary.trips)
        append(", треков: ").append(model.summary.tracks).append("\n")
        append("- Завершено: ").append(model.summary.completed)
        append(", скипов: ").append(model.summary.skipped)
        append(", прервано: ").append(model.summary.interrupted).append("\n\n")
        append("## Качество данных\n\n")
        append("- Длительность известна: ").append(pct(model.quality.durationCoverage)).append("\n")
        append("- Позиция известна: ").append(pct(model.quality.positionCoverage)).append("\n")
        append("- Громкость MCU известна: ").append(pct(model.quality.mcuCoverage)).append("\n")
        append("- Разрывов сбора: ").append(model.quality.captureGaps).append("\n")
        append("- Открытых: ").append(model.quality.open)
        append(", восстановленных: ").append(model.quality.restored)
        append(", непроверенных дат: ").append(model.quality.unverified).append("\n\n")
        append("## Слушал чаще\n\n")
        model.top.forEachIndexed { index, track ->
            append(index + 1).append(". ").append(track.displayTitle)
            track.artist?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
            append(": ").append(track.plays).append(" play, completion ")
            append(pct(track.completion)).append(", скипов ").append(pct(track.skipRate))
            append(", оценка ").append(track.affinity?.toString() ?: "—").append("\n")
        }
        append("\n## Пропускал чаще\n\n")
        model.antiTop.forEachIndexed { index, track ->
            append(index + 1).append(". ").append(track.displayTitle)
            track.artist?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
            append(": скипов ").append(pct(track.skipRate))
            append(", completion ").append(pct(track.completion))
            append(", оценка ").append(track.affinity?.toString() ?: "—").append("\n")
        }
        if (model.candidates.isNotEmpty()) {
            append("\n## Кандидаты «послушать ещё»\n\n")
            model.candidates.take(10).forEach { track ->
                append("- ").append(track.displayTitle)
                append(": ").append(track.antiTopReason.orEmpty()).append("\n")
            }
        }
        append("\n## Задание\n\n")
        append("Дай 5 наблюдений о вкусах и 3 рекомендации, что послушать или переслушать. ")
        append("Отдельно отметь, где данных недостаточно.\n")
    }

    private fun periodLabel(model: ReportModel): String =
        if (model.period.days == null) "вся история" else model.period.label

    private fun duration(ms: Long): String {
        if (ms <= 0) return "0 мин"
        val totalMinutes = ms / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) hours.toString() + " ч " + minutes + " мин" else minutes.toString() + " мин"
    }

    private fun pct(value: Double?): String =
        if (value == null) "—" else (value * 100).toInt().toString() + "%"

    private fun volume(value: Double?): String =
        if (value == null) "—" else String.format(Locale.US, "%+.1f", value)

    private fun date(epochMs: Long): String = Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))

    private fun escapeCsv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
