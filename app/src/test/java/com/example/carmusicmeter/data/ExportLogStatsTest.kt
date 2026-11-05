package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportLogStatsTest {

    @Test
    fun `сканер считает события границы времени и непроверенные`() {
        val lines = sequenceOf(
            """{"ts":1793859523984,"type":"fyt_metadata_service","bootId":"b5b4b200","monoMs":49858,"clockVerified":true}""",
            """{"ts":1683706439724,"type":"accessibility_connected","bootId":"54034c83","clockVerified":false}""",
            """{"ts":1683706439737,"type":"service_started","clockVerified":false}""",
            """""",
        )

        val stats = ExportLogScanner.scan("logs/events-unverified.jsonl", lines)

        assertEquals(3, stats.events)
        assertEquals(1_683_706_439_724L, stats.fromMs)
        assertEquals(1_793_859_523_984L, stats.toMs)
        assertEquals(2, stats.unverified)
        assertTrue(stats.clockChanged.not())
    }

    @Test
    fun `сканер замечает clock_changed`() {
        val lines = sequenceOf(
            """{"ts":1793892693340,"type":"clock_changed","wallDelta":110186210096,"monoDelta":507}""",
        )

        val stats = ExportLogScanner.scan("logs/events-2026-11-05.jsonl", lines)

        assertTrue(stats.clockChanged)
        assertFalse(stats.fullyUnverified)
    }

    @Test
    fun `журнал целиком из непроверенных событий помечается`() {
        val lines = sequenceOf(
            """{"ts":1,"type":"a","clockVerified":false}""",
            """{"ts":2,"type":"b","clockVerified":false}""",
        )

        assertTrue(ExportLogScanner.scan("logs/events-unverified.jsonl", lines).fullyUnverified)
    }

    @Test
    fun `пустой журнал не даёт границ`() {
        val stats = ExportLogScanner.scan("logs/events-2026-11-05.jsonl", emptySequence())

        assertEquals(0, stats.events)
        assertNull(stats.fromMs)
        assertNull(stats.toMs)
    }

    @Test
    fun `покрытие считается по событиям и пропускает непроверенный журнал`() {
        val stats = listOf(
            ExportLogStats(
                path = "logs/events-unverified.jsonl",
                events = 78,
                fromMs = 1_683_706_439_724L,
                toMs = 1_683_706_500_000L,
                unverified = 78,
            ),
            ExportLogStats(
                path = "logs/events-2026-11-03.jsonl",
                events = 340,
                fromMs = 1_793_686_157_744L,
                toMs = 1_793_720_523_897L,
            ),
            ExportLogStats(
                path = "logs/events-2026-11-05.jsonl",
                events = 206,
                fromMs = 1_793_859_523_984L,
                toMs = 1_793_893_253_551L,
            ),
        )

        val coverage = exportCoverage(stats, ExportCoverage(0, 0))

        assertEquals(1_793_686_157_744L, coverage.fromMs)
        assertEquals(1_793_893_253_551L, coverage.toMs)
    }

    @Test
    fun `без датированных журналов покрытие берётся из плана`() {
        val stats = listOf(
            ExportLogStats(path = "logs/events-unverified.jsonl", events = 5, fromMs = 1, toMs = 2),
        )

        val coverage = exportCoverage(stats, ExportCoverage(10, 20))

        assertEquals(10L, coverage.fromMs)
        assertEquals(20L, coverage.toMs)
    }
}
