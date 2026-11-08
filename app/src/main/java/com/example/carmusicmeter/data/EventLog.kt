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
import java.util.concurrent.atomic.AtomicLong
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
    private val baseDir: File,
    /** Идентификатор процесса: новый при каждом старте процесса. */
    val bootId: String = newBootId(),
    /**
     * Монотонные миллисекунды на момент записи пачки. Вынесены параметром,
     * чтобы JVM-тесты не зависели от Android-часов.
     */
    private val monoClock: () -> Long = { SystemClock.elapsedRealtime() },
    /**
     * Можно ли верить настенным часам. Вынесено параметром, чтобы тесты не
     * зависели от глобального [ClockStatus].
     */
    private val clockVerified: () -> Boolean = { ClockStatus.verified },
) {

    val logsDir = File(baseDir, "logs").apply { mkdirs() }
    val snapshotsDir = File(baseDir, "snapshots").apply { mkdirs() }
    val playsDir = File(baseDir, "plays").apply { mkdirs() }
    val reportDir = File(baseDir, "report").apply { mkdirs() }

    /** Сколько событий записано с непроверенным временем в этом процессе. */
    private val unverifiedCount = AtomicLong(0)
    val unverifiedEvents: Long get() = unverifiedCount.get()

    fun todayFile(nowMs: Long = System.currentTimeMillis()): File =
        File(logsDir, "events-${dayStamp(nowMs)}.jsonl")

    /** Журнал непроверенного времени: без даты в имени, чтобы она не врала. */
    val unverifiedFile: File get() = File(logsDir, UNVERIFIED_LOG_NAME)

    /**
     * Файл, в который попадёт событие: по дате, если часам можно верить,
     * иначе — общий непроверенный поток.
     */
    fun logFileFor(ts: Long, verified: Boolean = clockVerified()): File =
        if (verified) todayFile(ts) else unverifiedFile

    fun append(event: LogEvent) = appendAll(listOf(event))

    /**
     * Закрытие прослушивания: событие и факт пишутся в одной операции.
     * Файлы разные, поэтому fsync у каждого свой; недостающий факт
     * восстанавливается из события при разборе истории.
     */
    fun appendClose(event: LogEvent, play: PlayRecord) = appendBatch(listOf(event), listOf(play))

    fun appendBatch(events: List<LogEvent>, plays: List<PlayRecord> = emptyList()) {
        if (events.isNotEmpty()) appendAll(events)
        if (plays.isNotEmpty()) appendPlays(plays)
    }

    fun appendPlays(plays: List<PlayRecord>) {
        if (plays.isEmpty()) return
        synchronized(this) {
            plays.groupBy(::playFileFor).forEach { (file, items) ->
                FileOutputStream(file, true).use { out ->
                    items.forEach { play ->
                        out.write((play.toJsonLine() + "\n").toByteArray(Charsets.UTF_8))
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
        }
    }

    fun playFileFor(play: PlayRecord): File =
        if (play.clockVerified) {
            File(playsDir, "plays-${dayStamp(play.startedAt)}.jsonl")
        } else {
            File(playsDir, "plays-unverified.jsonl")
        }

    /** Открытый факт для отчёта; `null` удаляет файл. */
    fun writeOpenPlay(play: PlayRecord?) {
        val file = File(playsDir, OPEN_PLAY_NAME)
        synchronized(this) {
            if (play == null) {
                file.delete()
            } else {
                file.writeText(play.toJsonLine(), Charsets.UTF_8)
            }
        }
    }

    fun appendAll(events: List<LogEvent>) {
        if (events.isEmpty()) return
        val verified = clockVerified()
        val file = logFileFor(events.last().ts, verified)
        val monoMs = monoClock()
        synchronized(this) {
            FileOutputStream(file, true).use { out ->
                events.forEach { event ->
                    val line = Json.eventToJson(
                        event = event,
                        bootId = bootId,
                        monoMs = monoMs,
                        clockVerified = verified,
                    )
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
                out.flush()
                out.fd.sync()
            }
        }
        if (!verified) unverifiedCount.addAndGet(events.size.toLong())
    }

    /** Счётчики по сегодняшнему файлу и непроверенному потоку: (всего, стартов). */
    fun todayCounts(): Pair<Int, Int> {
        var events = 0
        var tracks = 0
        listOf(todayFile(), unverifiedFile).forEach { file ->
            runCatching {
                file.forEachLine { line ->
                    if (line.isNotBlank()) {
                        events += 1
                        if (line.contains("\"type\":\"track_started\"")) tracks += 1
                    }
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
        val plays = playsDir.listFiles()?.filter { it.isFile }.orEmpty()
        val cache = PlayCache.cacheDir(baseDir).listFiles()?.filter { it.isFile }.orEmpty()
        return StorageStats(
            oldestLogDate = logs.mapNotNull { it.date }.minOrNull(),
            logFiles = logs.size,
            logBytes = logs.sumOf { it.size },
            snapshotFiles = snapshots.size,
            snapshotBytes = snapshots.sumOf { it.size },
            crashFiles = crashes.size,
            crashBytes = crashes.sumOf { it.size },
            playFiles = plays.size,
            playBytes = plays.sumOf { it.length() },
            cacheFiles = cache.size,
            cacheBytes = cache.sumOf { it.length() },
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

        var deletedPlays = 0
        var deletedPlayBytes = 0L
        var deletedCache = 0
        var deletedCacheBytes = 0L
        plan.logs.mapNotNull { it.date }.distinct().forEach { day ->
            val play = File(playsDir, "plays-$day.jsonl")
            if (play.exists()) {
                val size = play.length()
                if (play.delete()) {
                    deletedPlays += 1
                    deletedPlayBytes += size
                }
            }
            val cache = File(PlayCache.cacheDir(baseDir), "plays-$day.jsonl")
            if (cache.exists()) {
                val size = cache.length()
                if (cache.delete()) {
                    deletedCache += 1
                    deletedCacheBytes += size
                }
            }
        }

        if (deleted.isNotEmpty() || deletedPlays > 0 || deletedCache > 0) {
            val reasons = plan.reasons().joinToString("+").ifBlank { "policy" }
            val deletedFiles = deleted.mapTo(HashSet()) { it.file }
            runCatching {
                append(
                    LogEvent(
                        ts = nowMs,
                        type = "retention_trimmed",
                        fields = linkedMapOf(
                            "files" to (deleted.size + deletedPlays + deletedCache),
                            "bytes" to (deleted.sumOf { it.size } + deletedPlayBytes + deletedCacheBytes),
                            "logs" to plan.logs.count { it.file in deletedFiles },
                            "snapshots" to plan.snapshots.count { it.file in deletedFiles },
                            "crashes" to plan.crashes.count { it.file in deletedFiles },
                            "plays" to deletedPlays,
                            "cache" to deletedCache,
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
        generated: List<ExportGenerated> = emptyList(),
        plays: ExportPlaysStats? = null,
        report: ExportReportStats? = null,
    ): Int {
        if (plan.files.isEmpty() && generated.isEmpty()) error("нет данных за период")
        val entries = ArrayList<ExportFile>(plan.files.size + generated.size)
        val logStats = ArrayList<ExportLogStats>()
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
                    // Покрытие выгрузки считается по временам событий, а не по
                    // датам в именах: имя могло соврать из-за сбитых часов.
                    if (stored.kind == StoredKind.LOG) {
                        logStats += ExportLogScanner.scanFile(path, file)
                    }
                }
                generated.forEach { entry ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.putNextEntry(ZipEntry(entry.path))
                    zip.write(entry.bytes)
                    zip.closeEntry()
                    entries += ExportFile(
                        path = entry.path,
                        size = entry.bytes.size.toLong(),
                        sha256 = digest.digest(entry.bytes).toHex(),
                    )
                }
                val manifest = Json.objectFrom(
                    ExportManifest.build(
                        info = info,
                        files = entries,
                        exportedAtMs = exportedAtMs,
                        range = plan.rangeInfo(),
                        coverage = exportCoverage(logStats, plan.coverage()),
                        logs = logStats,
                        plays = plays,
                        report = report,
                    ),
                )
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return entries.size
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

    companion object {
        /**
         * Журнал событий, записанных при непроверенных часах.
         *
         * Имя без даты — намеренно: дата, которой нельзя верить, не должна
         * попадать ни в имя файла, ни в `coverage` выгрузки.
         */
        const val UNVERIFIED_LOG_NAME = "events-unverified.jsonl"
        const val OPEN_PLAY_NAME = "open.json"
    }
}

/**
 * Короткий идентификатор процесса для конверта событий.
 *
 * Достаточно уникальности в пределах устройства: отчёт использует его, чтобы
 * понять, какие события принадлежат одному запуску службы.
 */
fun newBootId(): String = UUID.randomUUID().toString().replace("-", "").take(8)
