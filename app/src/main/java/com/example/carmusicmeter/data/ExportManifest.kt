package com.example.carmusicmeter.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Сведения об устройстве и выгрузке, попадающие в `manifest.json`.
 *
 * Манифест делает архив самодостаточным: по нему видно, с какого устройства
 * и какой выгрузкой получен ZIP, даже если файл переименован. Это нужно,
 * чтобы будущая синхронизация (локальная сеть, Bluetooth, флешка) могла
 * склеивать выгрузки идемпотентно, без разбора содержимого журнала.
 */
data class ExportInfo(
    val deviceId: String,
    val exportSeq: Long,
    val appVersion: String,
    val versionCode: Int,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
)

/** Файл внутри ZIP: путь, размер и SHA-256 содержимого. */
data class ExportFile(
    val path: String,
    val size: Long,
    val sha256: String,
)

/** Артефакт, сгенерированный приложением и записанный в ZIP без файла на диске. */
data class ExportGenerated(
    val path: String,
    val bytes: ByteArray,
)

/** Сводка фактов прослушиваний для `manifest.json`. */
data class ExportPlaysStats(
    val total: Int,
    val live: Int,
    val history: Int,
    val open: Int,
    val rated: Int,
    val censored: Int,
    val fromMs: Long?,
    val toMs: Long?,
    val playsSchemaVersion: Int = PlayRecord.SCHEMA_VERSION,
)

/** Сводка отчёта для `manifest.json`. */
data class ExportReportStats(
    val period: String,
    val days: Int?,
    val tracks: Int,
    val plays: Int,
    val generatedAtMs: Long,
    val version: Int = 1,
    val scoredTracks: Int = 0,
    val volumeCoverage: Double? = null,
    val affinityVersion: String = "v1",
)

object ExportManifest {

    /** Версия формата manifest.json; меняется только при несовместимых правках полей. */
    const val SCHEMA_VERSION = 2

    fun build(
        info: ExportInfo,
        files: List<ExportFile>,
        exportedAtMs: Long,
        range: ExportRangeInfo? = null,
        coverage: ExportCoverage? = null,
        logs: List<ExportLogStats> = emptyList(),
        plays: ExportPlaysStats? = null,
        report: ExportReportStats? = null,
    ): Map<String, Any?> {
        val manifest = linkedMapOf<String, Any?>(
            "kind" to "carmusicmeter-export",
            "schemaVersion" to SCHEMA_VERSION,
            "deviceId" to info.deviceId,
            "exportSeq" to info.exportSeq,
            "exportedAt" to isoTime(exportedAtMs),
            "exportedAtMs" to exportedAtMs,
            "appVersion" to info.appVersion,
            "versionCode" to info.versionCode,
            "device" to linkedMapOf(
                "manufacturer" to info.manufacturer,
                "model" to info.model,
                "android" to info.androidRelease,
                "sdk" to info.sdkInt,
            ),
        )
        if (range != null) {
            manifest["range"] = linkedMapOf(
                "mode" to range.mode,
                "days" to range.days,
                "fromMs" to range.fromMs,
                "toMs" to range.toMs,
            )
        }
        if (coverage != null) {
            manifest["coverage"] = linkedMapOf(
                "fromMs" to coverage.fromMs,
                "toMs" to coverage.toMs,
            )
        }
        if (logs.isNotEmpty()) {
            manifest["logs"] = logs.map { log ->
                linkedMapOf(
                    "path" to log.path,
                    "events" to log.events,
                    "fromMs" to log.fromMs,
                    "toMs" to log.toMs,
                    "unverified" to log.unverified,
                    "clockChanged" to log.clockChanged,
                )
            }
        }
        if (plays != null) {
            manifest["plays"] = linkedMapOf(
                "playsSchemaVersion" to plays.playsSchemaVersion,
                "total" to plays.total,
                "live" to plays.live,
                "history" to plays.history,
                "open" to plays.open,
                "rated" to plays.rated,
                "censored" to plays.censored,
                "fromMs" to plays.fromMs,
                "toMs" to plays.toMs,
            )
        }
        if (report != null) {
            manifest["report"] = linkedMapOf(
                "period" to report.period,
                "days" to report.days,
                "tracks" to report.tracks,
                "plays" to report.plays,
                "generatedAt" to isoTime(report.generatedAtMs),
                "generatedAtMs" to report.generatedAtMs,
                "version" to report.version,
                "scoredTracks" to report.scoredTracks,
                "volumeCoverage" to report.volumeCoverage,
                "affinityVersion" to report.affinityVersion,
            )
        }
        manifest["files"] = files.map { file ->
            linkedMapOf(
                "path" to file.path,
                "size" to file.size,
                "sha256" to file.sha256,
            )
        }
        manifest["totalBytes"] = files.sumOf { it.size }
        return manifest
    }

    /** Локальное время с явным смещением: `2026-10-31T18:30:00.123+03:00`. */
    fun isoTime(ms: Long): String = Instant.ofEpochMilli(ms)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
