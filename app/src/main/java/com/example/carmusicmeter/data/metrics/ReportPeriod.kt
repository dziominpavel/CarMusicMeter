package com.example.carmusicmeter.data.metrics

import com.example.carmusicmeter.data.ExportRange
import com.example.carmusicmeter.data.StorageDates

/** Период отчёта: те же пресеты, что у экспорта, но с сохранением в настройках. */
enum class ReportPeriod(
    val days: Int?,
    val tag: String,
    val label: String,
) {
    DAYS_7(7, "7d", "7 дней"),
    DAYS_30(30, "30d", "30 дней"),
    DAYS_90(90, "90d", "90 дней"),
    ALL(null, "all", "Всё"),
    ;

    fun fromMs(nowMs: Long): Long? = days?.let {
        StorageDates.startOfDayMs(StorageDates.dateOf(nowMs).minusDays(it - 1L))
    }

    companion object {
        fun fromTag(tag: String?): ReportPeriod =
            entries.firstOrNull { it.tag == tag } ?: DAYS_7
    }
}

fun ExportRange.toReportPeriod(): ReportPeriod = when (this) {
    ExportRange.DAYS_7 -> ReportPeriod.DAYS_7
    ExportRange.DAYS_30 -> ReportPeriod.DAYS_30
    ExportRange.DAYS_90 -> ReportPeriod.DAYS_90
    ExportRange.ALL -> ReportPeriod.ALL
}
