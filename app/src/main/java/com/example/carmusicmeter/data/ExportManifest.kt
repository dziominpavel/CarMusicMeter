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

object ExportManifest {

    /** Версия формата manifest.json; меняется только при несовместимых правках полей. */
    const val SCHEMA_VERSION = 1

    fun build(
        info: ExportInfo,
        files: List<ExportFile>,
        exportedAtMs: Long,
    ): Map<String, Any?> = linkedMapOf(
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
        "files" to files.map { file ->
            linkedMapOf(
                "path" to file.path,
                "size" to file.size,
                "sha256" to file.sha256,
            )
        },
        "totalBytes" to files.sumOf { it.size },
    )

    /** Локальное время с явным смещением: `2026-10-31T18:30:00.123+03:00`. */
    fun isoTime(ms: Long): String = Instant.ofEpochMilli(ms)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
