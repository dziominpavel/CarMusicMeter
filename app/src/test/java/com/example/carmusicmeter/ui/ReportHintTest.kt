package com.example.carmusicmeter.ui

import com.example.carmusicmeter.data.metrics.ReportPeriod
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportHintTest {

    private fun targets(period: ReportPeriod): String =
        periodSwitchHint(period)!!.substringAfter("Переключите на ")

    @Test
    fun `для всей истории подсказки нет`() {
        assertNull(periodSwitchHint(ReportPeriod.ALL))
    }

    @Test
    fun `подсказка не предлагает уже выбранный период`() {
        for (period in ReportPeriod.entries) {
            if (period == ReportPeriod.ALL) continue
            val offered = targets(period)

            assertFalse(
                "подсказка для $period предлагает сам $period: $offered",
                offered.contains("«" + period.label + "»"),
            )
            assertTrue(
                "подсказка для $period не предлагает всю историю: $offered",
                offered.contains("«" + ReportPeriod.ALL.label + "»"),
            )
        }
    }

    @Test
    fun `предлагается ближайший более широкий период`() {
        assertTrue(targets(ReportPeriod.DAYS_7).contains("«" + ReportPeriod.DAYS_30.label + "»"))
        assertTrue(targets(ReportPeriod.DAYS_30).contains("«" + ReportPeriod.DAYS_90.label + "»"))

        val fromNinetyDays = targets(ReportPeriod.DAYS_90)
        assertFalse(fromNinetyDays.contains("«" + ReportPeriod.DAYS_7.label + "»"))
        assertFalse(fromNinetyDays.contains("«" + ReportPeriod.DAYS_30.label + "»"))
    }
}
