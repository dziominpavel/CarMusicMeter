package com.example.carmusicmeter.data

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePolicyTest {

    private val today = LocalDate.of(2026, 3, 1)

    @Test
    fun `по умолчанию журнал хранится бессрочно с лимитом 500 МБ`() {
        val policy = StoragePolicy.DEFAULT

        assertEquals(0, policy.retentionDays)
        assertEquals(500L * 1024L * 1024L, policy.maxBytes)
        assertEquals(null, policy.cutoff(today))
    }

    @Test
    fun `срок хранения отсекает старые дни и не трогает сегодняшний`() {
        val ancient = stored("events-2026-01-01.jsonl", LocalDate.of(2026, 1, 1))
        val recent = stored("events-2026-02-25.jsonl", LocalDate.of(2026, 2, 25))
        val current = stored("events-2026-03-01.jsonl", today)

        val plan = RetentionPlanner.plan(
            logs = listOf(ancient, recent, current),
            snapshots = emptyList(),
            crashes = emptyList(),
            policy = StoragePolicy(retentionDays = 30),
            today = today,
        )

        assertEquals(listOf(ancient), plan.logs)
        assertFalse(plan.logs.contains(current))
    }

    @Test
    fun `лимит объёма удаляет самые старые завершённые дни`() {
        val oldest = stored("events-2026-02-01.jsonl", LocalDate.of(2026, 2, 1), size = 100)
        val middle = stored("events-2026-02-10.jsonl", LocalDate.of(2026, 2, 10), size = 100)
        val current = stored("events-2026-03-01.jsonl", today, size = 100)

        val plan = RetentionPlanner.plan(
            logs = listOf(middle, current, oldest),
            snapshots = emptyList(),
            crashes = emptyList(),
            policy = StoragePolicy(maxBytes = 250),
            today = today,
        )

        assertEquals(listOf(oldest), plan.logs)
    }

    @Test
    fun `файл с неразборчивым именем не удаляется автоматически`() {
        val unknown = stored("weird-name.jsonl", null)

        val plan = RetentionPlanner.plan(
            logs = listOf(unknown),
            snapshots = emptyList(),
            crashes = emptyList(),
            policy = StoragePolicy(retentionDays = 1, maxBytes = 1),
            today = today,
        )

        assertTrue(plan.logs.isEmpty())
    }

    @Test
    fun `журнал непроверенного времени не удаляется ни по сроку, ни по объёму`() {
        val unverified = stored(EventLog.UNVERIFIED_LOG_NAME, null, size = 1_000)
        val old = stored("events-2026-01-01.jsonl", LocalDate.of(2026, 1, 1), size = 100)

        val plan = RetentionPlanner.plan(
            logs = listOf(unverified, old),
            snapshots = emptyList(),
            crashes = emptyList(),
            policy = StoragePolicy(retentionDays = 1, maxBytes = 1),
            today = today,
        )

        assertEquals(listOf(old), plan.logs)
        assertFalse(plan.logs.contains(unverified))
    }

    @Test
    fun `снимки и crash-файлы ограничены количеством`() {
        val snapshots = (0..600).map { index ->
            val file = File("snapshot-20260101-1200${index.toString().padStart(2, '0')}-manual.json")
            StoredFile(file, StoredKind.SNAPSHOT, LocalDate.of(2026, 1, 1), size = 1, lastModified = index.toLong())
        }
        val crashes = (0..50).map { index ->
            val file = File("crash-${1_700_000_000_000L + index}.txt")
            StoredFile(file, StoredKind.CRASH, LocalDate.of(2026, 1, 1), size = 1, lastModified = index.toLong())
        }

        val plan = RetentionPlanner.plan(
            logs = emptyList(),
            snapshots = snapshots,
            crashes = crashes,
            policy = StoragePolicy.DEFAULT,
            today = today,
        )

        assertEquals(1, plan.snapshots.size)
        assertEquals(1, plan.crashes.size)
    }

    @Test
    fun `StorageDates разбирает имена журнала снимка и crash`() {
        assertEquals(LocalDate.of(2026, 10, 31), StorageDates.logDate("events-2026-10-31.jsonl"))
        assertEquals(LocalDate.of(2026, 10, 31), StorageDates.snapshotDate("snapshot-20261031-184330-manual.json"))
        assertEquals(1_793_467_800_123L, StorageDates.crashTime("crash-1793467800123.txt"))
    }

    private fun stored(
        name: String,
        date: LocalDate?,
        size: Long = 100,
    ): StoredFile = StoredFile(
        file = File(name),
        kind = StoredKind.LOG,
        date = date,
        size = size,
    )
}
