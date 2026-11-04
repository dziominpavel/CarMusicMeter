package com.example.carmusicmeter.data

import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Журнал событий: одна строка JSONL на событие, файл на календарный день.
 *
 * Каждая пачка событий пишется с fsync: магнитола выключается вместе с
 * зажиганием, и буфер в памяти потерялся бы. События редкие (смена трека,
 * пауза, громкость), поэтому цена fsync незаметна.
 *
 * Каждое событие получает конверт `bootId` + `monoMs`: по ним отчёт может
 * упорядочить события и восстановить даты, если часы магнитолы сбились.
 */
class EventLog(
    baseDir: File,
    /** Идентификатор процесса: новый при каждом старте процесса. */
    val bootId: String = newBootId(),
    /**
     * Монотонные миллисекунды на момент записи пачки. Вынесены параметром,
     * чтобы JVM-тесты не зависели от Android-часов.
     */
    private val monoClock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    val logsDir = File(baseDir, "logs").apply { mkdirs() }
    val snapshotsDir = File(baseDir, "snapshots").apply { mkdirs() }

    fun todayFile(nowMs: Long = System.currentTimeMillis()): File =
        File(logsDir, "events-${dayStamp(nowMs)}.jsonl")

    fun append(event: LogEvent) = appendAll(listOf(event))

    fun appendAll(events: List<LogEvent>) {
        if (events.isEmpty()) return
        val file = todayFile(events.last().ts)
        val monoMs = monoClock()
        synchronized(this) {
            FileOutputStream(file, true).use { out ->
                events.forEach { event ->
                    val line = Json.eventToJson(event, bootId = bootId, monoMs = monoMs)
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
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

    fun storageStats(): StorageStats {
        val logs = scanLogs()
        val snapshots = scanSnapshots()
        val crashes = scanCrashes()
        return StorageStats(
            oldestLogDate = logs.mapNotNull { it.date }.minOrNull(),
            logFiles = logs.size,
            logBytes = logs.sumOf { it.size },
            snapshotFiles = snapshots.size,
            snapshotBytes = snapshots.sumOf { it.size },
            crashFiles = crashes.size,
            crashBytes = crashes.sumOf { it.size },
        )
    }

    /**
     * Чистка по политике: журнал — по дате из имени и лимиту объёма,
     * снимки и crash-файлы — по количеству. Текущий день не удаляется.
     */
    fun planTrim(
        policy: StoragePolicy = StoragePolicy.DEFAULT,
        nowMs: Long = System.currentTimeMillis(),
    ): RetentionPlan = RetentionPlanner.plan(
        logs = scanLogs(),
        snapshots = scanSnapshots(),
        crashes = scanCrashes(),
        policy = policy,
        today = StorageDates.dateOf(nowMs),
    )

    fun trimOld(
        policy: StoragePolicy = StoragePolicy.DEFAULT,
        nowMs: Long = System.currentTimeMillis(),
    ): RetentionPlan {
        val plan = planTrim(policy, nowMs)
        val deleted = plan.all.filter { it.file.delete() }
        if (deleted.isNotEmpty()) {
            val reasons = plan.reasons().joinToString("+").ifBlank { "policy" }
            val deletedFiles = deleted.mapTo(HashSet()) { it.file }
            runCatching {
                append(
                    LogEvent(
                        ts = nowMs,
                        type = "retention_trimmed",
                        fields = linkedMapOf(
                            "files" to deleted.size,
                            "bytes" to deleted.sumOf { it.size },
                            "logs" to plan.logs.count { it.file in deletedFiles },
                            "snapshots" to plan.snapshots.count { it.file in deletedFiles },
                            "crashes" to plan.crashes.count { it.file in deletedFiles },
                            "reason" to reasons,
                        ),
                    ),
                )
            }
        }
        return plan
    }

    fun planExport(
        range: ExportRange,
        nowMs: Long = System.currentTimeMillis(),
    ): ExportPlan = ExportPlanner.plan(
        files = scanLogs() + scanSnapshots() + scanCrashes(),
        range = range,
        today = StorageDates.dateOf(nowMs),
        nowMs = nowMs,
    )

    /**
     * Упаковать выбранные файлы в ZIP, выбранный пользователем через SAF.
     *
     * Контрольные суммы считаются по ходу копирования, без второго прохода
     * по файлам: на магнитоле дорога каждая секунда чтения флешки.
     */
    fun exportTo(
        resolver: ContentResolver,
        uri: Uri,
        info: ExportInfo,
        plan: ExportPlan,
        exportedAtMs: Long = System.currentTimeMillis(),
    ): Int {
        if (plan.files.isEmpty()) error("нет данных за период")
        val entries = ArrayList<ExportFile>(plan.files.size)
        val stream = resolver.openOutputStream(uri)
            ?: error("не удалось открыть поток для записи")
        stream.use { output ->
            ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                plan.files.forEach { stored ->
                    val file = stored.file
                    val path = "${stored.kind.dir}/${file.name}"
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    zip.putNextEntry(ZipEntry(path))
                    DigestInputStream(file.inputStream().buffered(), digest).use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            zip.write(buffer, 0, read)
                            size += read
                        }
                    }
                    zip.closeEntry()
                    entries += ExportFile(path, size, digest.digest().toHex())
                }
                val manifest = Json.objectFrom(
                    ExportManifest.build(
                        info = info,
                        files = entries,
                        exportedAtMs = exportedAtMs,
                        range = plan.rangeInfo(),
                        coverage = plan.coverage(),
                    ),
                )
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return plan.files.size
    }

    private fun scanLogs(): List<StoredFile> =
        logsDir.listFiles()
            ?.filterNot { it.name.startsWith("crash-") }
            ?.map { file ->
                StoredFile(
                    file = file,
                    kind = StoredKind.LOG,
                    date = StorageDates.logDate(file.name),
                )
            }
            .orEmpty()

    private fun scanSnapshots(): List<StoredFile> =
        snapshotsDir.listFiles()
            ?.map { file ->
                StoredFile(
                    file = file,
                    kind = StoredKind.SNAPSHOT,
                    date = StorageDates.snapshotDate(file.name),
                )
            }
            .orEmpty()

    private fun scanCrashes(): List<StoredFile> =
        logsDir.listFiles()
            ?.filter { it.name.startsWith("crash-") }
            ?.map { file ->
                StoredFile(
                    file = file,
                    kind = StoredKind.CRASH,
                    date = StorageDates.crashTime(file.name)?.let(StorageDates::dateOf),
                )
            }
            .orEmpty()

    private fun dayStamp(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))

    private fun fileStamp(ts: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(ts))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/**
 * Короткий идентификатор процесса для конверта событий.
 *
 * Достаточно уникальности в пределах устройства: отчёт использует его, чтобы
 * понять, какие события принадлежат одному запуску службы.
 */
fun newBootId(): String = UUID.randomUUID().toString().replace("-", "").take(8)
