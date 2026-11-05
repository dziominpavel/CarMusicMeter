package com.example.carmusicmeter.data

import java.time.LocalDate

enum class ExportRange(
    val days: Int?,
    val tag: String,
    val label: String,
) {
    ALL(null, "all", "Вся история"),
    DAYS_90(90, "90d", "90 дней"),
    DAYS_30(30, "30d", "30 дней"),
    DAYS_7(7, "7d", "7 дней"),
    ;

    fun cutoff(today: LocalDate): LocalDate? =
        days?.let { today.minusDays(it.toLong() - 1L) }

    companion object {
        fun fromTag(tag: String?): ExportRange =
            entries.firstOrNull { it.tag == tag } ?: ALL
    }
}

data class ExportRangeInfo(
    val mode: String,
    val days: Int?,
    val fromMs: Long?,
    val toMs: Long?,
)

data class ExportCoverage(
    val fromMs: Long?,
    val toMs: Long?,
)

data class ExportPlan(
    val files: List<StoredFile>,
    val range: ExportRange,
    val totalBytes: Long,
    val coverageFromMs: Long?,
    val coverageToMs: Long?,
    val requestedFromMs: Long?,
    val requestedToMs: Long?,
) {
    val isEmpty: Boolean get() = files.isEmpty()

    fun rangeInfo(): ExportRangeInfo = ExportRangeInfo(
        mode = if (range.days == null) "all" else "days",
        days = range.days,
        fromMs = requestedFromMs,
        toMs = requestedToMs,
    )

    fun coverage(): ExportCoverage = ExportCoverage(
        fromMs = coverageFromMs,
        toMs = coverageToMs,
    )
}

object ExportPlanner {

    fun plan(
        files: List<StoredFile>,
        range: ExportRange,
        today: LocalDate,
        nowMs: Long,
    ): ExportPlan {
        val cutoff = range.cutoff(today)
        val included = if (cutoff == null) {
            files
        } else {
            // Журнал непроверенного времени даты в имени не имеет, а его
            // lastModified ничего не говорит о времени записи: он попадает в
            // любую выгрузку, иначе события со сбитыми часами терялись бы при
            // выгрузке за 7 или 30 дней.
            files.filter { file -> isUndatedLog(file) || !effectiveDate(file).isBefore(cutoff) }
        }
        val dates = included.map(::effectiveDate)
        val from = dates.minOrNull()?.let(StorageDates::startOfDayMs)
        val to = dates.maxOrNull()?.let(StorageDates::endOfDayMs)
        return ExportPlan(
            files = included.sortedWith(compareBy({ it.kind.ordinal }, { it.file.name })),
            range = range,
            totalBytes = included.sumOf { it.size },
            coverageFromMs = from,
            coverageToMs = to,
            requestedFromMs = cutoff?.let(StorageDates::startOfDayMs),
            requestedToMs = if (cutoff == null) null else nowMs,
        )
    }

    /** Журнал без даты в имени: время записи в нём не проверено. */
    private fun isUndatedLog(file: StoredFile): Boolean =
        file.kind == StoredKind.LOG && file.date == null

    private fun effectiveDate(file: StoredFile): LocalDate =
        file.date ?: StorageDates.dateOf(file.lastModified)
}
