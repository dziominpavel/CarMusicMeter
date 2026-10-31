package com.example.carmusicmeter.data

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

enum class StoredKind(val dir: String) {
    LOG("logs"),
    SNAPSHOT("snapshots"),
    CRASH("logs"),
}

data class StoredFile(
    val file: File,
    val kind: StoredKind,
    val date: LocalDate?,
    val size: Long = file.length(),
    val lastModified: Long = file.lastModified(),
)

data class StoragePolicy(
    /** 0 — хранить бессрочно. */
    val retentionDays: Int = 0,
    /** 0 — без лимита объёма. */
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    fun cutoff(today: LocalDate): LocalDate? =
        if (retentionDays > 0) today.minusDays(retentionDays.toLong()) else null

    companion object {
        const val MAX_SNAPSHOTS = 600
        const val MAX_CRASH_FILES = 50
        const val DEFAULT_MAX_BYTES = 500L * 1024L * 1024L

        val DEFAULT = StoragePolicy()
    }
}

data class StorageStats(
    val oldestLogDate: LocalDate? = null,
    val logFiles: Int = 0,
    val logBytes: Long = 0,
    val snapshotFiles: Int = 0,
    val snapshotBytes: Long = 0,
    val crashFiles: Int = 0,
    val crashBytes: Long = 0,
) {
    val totalBytes: Long get() = logBytes + snapshotBytes + crashBytes
    val totalFiles: Int get() = logFiles + snapshotFiles + crashFiles
}

data class RetentionPlan(
    val logs: List<StoredFile> = emptyList(),
    val snapshots: List<StoredFile> = emptyList(),
    val crashes: List<StoredFile> = emptyList(),
) {
    val all: List<StoredFile> get() = logs + snapshots + crashes
    val totalBytes: Long get() = all.sumOf { it.size }
    val isEmpty: Boolean get() = all.isEmpty()

    fun reasons(): List<String> = buildList {
        if (logs.isNotEmpty()) add("age_or_size")
        if (snapshots.isNotEmpty()) add("snapshots")
        if (crashes.isNotEmpty()) add("crashes")
    }
}

object StorageDates {

    private val logPattern = Regex("""^events-(\d{4}-\d{2}-\d{2})\.jsonl$""")
    private val snapshotPattern = Regex("""^snapshot-(\d{8})-\d{6}.*\.json$""")
    private val crashPattern = Regex("""^crash-(\d+)\.txt$""")
    private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val compactFormat = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun logDate(name: String): LocalDate? =
        logPattern.matchEntire(name)?.groupValues?.get(1)?.let { parseDay(it) }

    fun snapshotDate(name: String): LocalDate? =
        snapshotPattern.matchEntire(name)?.groupValues?.get(1)?.let {
            runCatching { LocalDate.parse(it, compactFormat) }.getOrNull()
        }

    fun crashTime(name: String): Long? =
        crashPattern.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()

    fun dateOf(epochMs: Long): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

    fun startOfDayMs(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun endOfDayMs(date: LocalDate): Long =
        date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

    private fun parseDay(value: String): LocalDate? =
        try {
            LocalDate.parse(value, dayFormat)
        } catch (_: DateTimeParseException) {
            null
        }
}

object RetentionPlanner {

    fun plan(
        logs: List<StoredFile>,
        snapshots: List<StoredFile>,
        crashes: List<StoredFile>,
        policy: StoragePolicy,
        today: LocalDate,
    ): RetentionPlan {
        val deletedLogs = mutableListOf<StoredFile>()
        val keptLogs = logs.toMutableList()

        policy.cutoff(today)?.let { cutoff ->
            keptLogs
                .filter { it.date != null && it.date.isBefore(cutoff) && it.date != today }
                .forEach {
                    deletedLogs += it
                    keptLogs.remove(it)
                }
        }

        if (policy.maxBytes > 0) {
            var total = keptLogs.sumOf { it.size }
            val candidates = keptLogs
                .filter { it.date != null && it.date != today }
                .sortedWith(compareBy<StoredFile> { it.date }.thenBy { it.lastModified })
            for (candidate in candidates) {
                if (total <= policy.maxBytes) break
                deletedLogs += candidate
                keptLogs.remove(candidate)
                total -= candidate.size
            }
        }

        val sortedSnapshots = snapshots.sortedWith(
            compareByDescending<StoredFile> { it.date ?: StorageDates.dateOf(it.lastModified) }
                .thenByDescending { it.lastModified },
        )
        val deletedSnapshots = sortedSnapshots.drop(StoragePolicy.MAX_SNAPSHOTS)

        val sortedCrashes = crashes.sortedWith(
            compareByDescending<StoredFile> { crashTime(it) }.thenByDescending { it.lastModified },
        )
        val deletedCrashes = sortedCrashes.drop(StoragePolicy.MAX_CRASH_FILES)

        return RetentionPlan(
            logs = deletedLogs,
            snapshots = deletedSnapshots,
            crashes = deletedCrashes,
        )
    }

    private fun crashTime(file: StoredFile): Long =
        StorageDates.crashTime(file.file.name) ?: file.lastModified
}
