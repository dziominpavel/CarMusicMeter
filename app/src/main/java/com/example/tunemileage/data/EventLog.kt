package com.example.tunemileage.data

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Журнал событий: одна строка JSONL на событие, файл на календарный день.
 *
 * Каждая пачка событий пишется с fsync: магнитола выключается вместе с
 * зажиганием, и буфер в памяти потерялся бы. События редкие (смена трека,
 * пауза, громкость), поэтому цена fsync незаметна.
 */
class EventLog(baseDir: File) {

    val logsDir = File(baseDir, "logs").apply { mkdirs() }
    val snapshotsDir = File(baseDir, "snapshots").apply { mkdirs() }

    fun todayFile(nowMs: Long = System.currentTimeMillis()): File =
        File(logsDir, "events-${dayStamp(nowMs)}.jsonl")

    fun append(event: LogEvent) = appendAll(listOf(event))

    fun appendAll(events: List<LogEvent>) {
        if (events.isEmpty()) return
        val file = todayFile(events.last().ts)
        synchronized(this) {
            FileOutputStream(file, true).use { out ->
                events.forEach { event ->
                    out.write((Json.eventToJson(event) + "\n").toByteArray(Charsets.UTF_8))
                }
                out.flush()
                out.fd.sync()
            }
        }
    }

    /** Счётчики по сегодняшнему файлу: (всего событий, стартов треков). */
    fun todayCounts(): Pair<Int, Int> {
        val file = todayFile()
        if (!file.exists()) return 0 to 0
        var events = 0
        var tracks = 0
        runCatching {
            file.forEachLine { line ->
                if (line.isNotBlank()) {
                    events += 1
                    if (line.contains("\"type\":\"track_started\"")) tracks += 1
                }
            }
        }
        return events to tracks
    }

    fun writeSnapshot(json: JSONObject, reason: String): File {
        val file = File(snapshotsDir, "snapshot-${fileStamp(System.currentTimeMillis())}-$reason.json")
        file.writeText(json.toString(2), Charsets.UTF_8)
        return file
    }

    /** Упаковать журнал и снимки в ZIP, выбранный пользователем через SAF. */
    fun exportTo(
        resolver: ContentResolver,
        uri: Uri,
        appVersion: String,
        versionCode: Int,
    ): Int {
        val files = buildList {
            logsDir.listFiles()?.sortedBy { it.name }?.let { addAll(it) }
            snapshotsDir.listFiles()?.sortedBy { it.name }?.let { addAll(it) }
        }
        val stream = resolver.openOutputStream(uri)
            ?: error("не удалось открыть поток для записи")
        stream.use { output ->
            ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                files.forEach { file ->
                    val dir = if (file.parentFile == logsDir) "logs" else "snapshots"
                    zip.putNextEntry(ZipEntry("$dir/${file.name}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                val manifest = Json.objectFrom(
                    mapOf(
                        "exportedAt" to System.currentTimeMillis(),
                        "appVersion" to appVersion,
                        "versionCode" to versionCode,
                        "files" to files.size,
                    ),
                )
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return files.size
    }

    /** Чистка старых файлов: журнал — 180 дней, снимки — последние 600 штук. */
    fun trimOld(maxAgeDays: Int = 180, maxSnapshots: Int = 600) {
        val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 60L * 60L * 1000L
        logsDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
        snapshotsDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(maxSnapshots)
            ?.forEach { it.delete() }
    }

    private fun dayStamp(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))

    private fun fileStamp(ts: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(ts))
}
