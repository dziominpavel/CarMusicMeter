package com.example.carmusicmeter.data

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportRangeTest {

    private val today = LocalDate.of(2026, 3, 1)
    private val now = StorageDates.startOfDayMs(today) + 12L * 60L * 60L * 1000L

    @Test
    fun `30 дней включают сегодня и предыдущие 29 дней`() {
        val old = stored("events-2026-01-20.jsonl", StoredKind.LOG, LocalDate.of(2026, 1, 20), 100)
        val recent = stored("events-2026-02-25.jsonl", StoredKind.LOG, LocalDate.of(2026, 2, 25), 200)
        val current = stored("events-2026-03-01.jsonl", StoredKind.LOG, today, 300)

        val plan = ExportPlanner.plan(listOf(old, recent, current), ExportRange.DAYS_30, today, now)

        assertEquals(listOf(recent, current), plan.files)
        assertEquals(500L, plan.totalBytes)
        assertEquals(StorageDates.startOfDayMs(LocalDate.of(2026, 2, 25)), plan.coverageFromMs)
        assertEquals(StorageDates.endOfDayMs(today), plan.coverageToMs)
        assertEquals("days", plan.rangeInfo().mode)
        assertEquals(30, plan.rangeInfo().days)
    }

    @Test
    fun `вся история включает все файлы`() {
        val old = stored("events-2026-01-01.jsonl", StoredKind.LOG, LocalDate.of(2026, 1, 1), 100)
        val current = stored("events-2026-03-01.jsonl", StoredKind.LOG, today, 100)

        val plan = ExportPlanner.plan(listOf(old, current), ExportRange.ALL, today, now)

        assertEquals(2, plan.files.size)
        assertEquals("all", plan.rangeInfo().mode)
        assertNull(plan.rangeInfo().fromMs)
        assertNull(plan.rangeInfo().toMs)
    }

    @Test
    fun `пустой период даёт пустой план`() {
        val old = stored("events-2026-01-01.jsonl", StoredKind.LOG, LocalDate.of(2026, 1, 1), 100)

        val plan = ExportPlanner.plan(listOf(old), ExportRange.DAYS_7, today, now)

        assertTrue(plan.isEmpty)
        assertEquals(0L, plan.totalBytes)
        assertNull(plan.coverageFromMs)
    }

    @Test
    fun `снимки и crash-файлы попадают в диапазон по своим датам`() {
        val snapshot = stored(
            "snapshot-20260225-120000-manual.json",
            StoredKind.SNAPSHOT,
            LocalDate.of(2026, 2, 25),
            50,
        )
        val crash = stored("crash-1793467800123.txt", StoredKind.CRASH, LocalDate.of(2026, 2, 25), 50)
        val oldSnapshot = stored(
            "snapshot-20260101-120000-manual.json",
            StoredKind.SNAPSHOT,
            LocalDate.of(2026, 1, 1),
            50,
        )

        val plan = ExportPlanner.plan(
            listOf(snapshot, crash, oldSnapshot),
            ExportRange.DAYS_30,
            today,
            now,
        )

        assertEquals(2, plan.files.size)
        assertTrue(plan.files.any { it.kind == StoredKind.SNAPSHOT && it.file.name == snapshot.file.name })
        assertTrue(plan.files.any { it.kind == StoredKind.CRASH })
        assertFalse(plan.files.any { it.file.name == oldSnapshot.file.name })
    }

    @Test
    fun `файл без даты в имени фильтруется по lastModified`() {
        val dated = LocalDate.of(2026, 2, 20)
        val file = StoredFile(
            file = File("weird-name.jsonl"),
            kind = StoredKind.LOG,
            date = null,
            size = 10,
            lastModified = StorageDates.startOfDayMs(dated),
        )

        val plan = ExportPlanner.plan(listOf(file), ExportRange.DAYS_30, today, now)

        assertEquals(1, plan.files.size)
    }

    @Test
    fun `журнал непроверенного времени попадает в любой период`() {
        val unverified = StoredFile(
            file = File(EventLog.UNVERIFIED_LOG_NAME),
            kind = StoredKind.LOG,
            date = null,
            size = 10,
            lastModified = StorageDates.startOfDayMs(LocalDate.of(2026, 1, 1)),
        )

        ExportRange.entries.forEach { range ->
            val plan = ExportPlanner.plan(listOf(unverified), range, today, now)
            assertTrue(
                "непроверенный журнал должен попасть в выгрузку за $range",
                plan.files.contains(unverified),
            )
        }
    }

    @Test
    fun `свежий снимок экспорта входит в план любого периода`() {
        val snapshot = stored(
            "snapshot-20260301-120000-export.json",
            StoredKind.SNAPSHOT,
            today,
            50,
        )
        val oldLog = stored("events-2026-01-01.jsonl", StoredKind.LOG, LocalDate.of(2026, 1, 1), 100)

        ExportRange.entries.forEach { range ->
            val plan = ExportPlanner.plan(listOf(oldLog, snapshot), range, today, now)
            assertTrue(
                "снимок должен попасть в архив за $range",
                plan.files.any { it.file.name == snapshot.file.name },
            )
            assertFalse(plan.isEmpty)
        }
    }

    private fun stored(name: String, kind: StoredKind, date: LocalDate, size: Long): StoredFile =
        StoredFile(
            file = File(name),
            kind = kind,
            date = date,
            size = size,
        )
}
