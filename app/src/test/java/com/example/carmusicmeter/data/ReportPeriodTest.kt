package com.example.carmusicmeter.data.metrics

import com.example.carmusicmeter.data.ExportRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportPeriodTest {

    @Test
    fun `теги периодов читаются и по умолчанию семь дней`() {
        assertEquals(ReportPeriod.DAYS_7, ReportPeriod.fromTag("7d"))
        assertEquals(ReportPeriod.DAYS_30, ReportPeriod.fromTag("30d"))
        assertEquals(ReportPeriod.DAYS_90, ReportPeriod.fromTag("90d"))
        assertEquals(ReportPeriod.ALL, ReportPeriod.fromTag("all"))
        assertEquals(ReportPeriod.DAYS_7, ReportPeriod.fromTag(null))
        assertEquals(ReportPeriod.DAYS_7, ReportPeriod.fromTag("unknown"))
    }

    @Test
    fun `граница периода считается от сегодняшнего дня`() {
        val now = 1_800_000_000_000L
        val from = ReportPeriod.DAYS_7.fromMs(now)

        assertTrue(from != null && from < now)
        assertNull(ReportPeriod.ALL.fromMs(now))
    }

    @Test
    fun `экспорт и отчёт используют одни пресеты`() {
        assertEquals(ReportPeriod.ALL, ExportRange.ALL.toReportPeriod())
        assertEquals(ReportPeriod.DAYS_90, ExportRange.DAYS_90.toReportPeriod())
        assertEquals(ReportPeriod.DAYS_30, ExportRange.DAYS_30.toReportPeriod())
        assertEquals(ReportPeriod.DAYS_7, ExportRange.DAYS_7.toReportPeriod())
    }
}
