package com.example.carmusicmeter.data

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventLogRetentionTest {

    private val root: File = Files.createTempDirectory("cmm-retention").toFile()
    private val today = LocalDate.of(2026, 3, 1)
    private val now = StorageDates.startOfDayMs(today) + 12L * 60L * 60L * 1000L
    private val log = EventLog(root, monoClock = { 0L }).apply {
        logsDir.mkdirs()
        snapshotsDir.mkdirs()
    }

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun `чистка удаляет старый день, оставляет сегодняшний и пишет событие`() {
        val old = file("logs/events-2026-01-01.jsonl", 100)
        val current = file("logs/events-2026-03-01.jsonl", 40)

        val plan = log.trimOld(StoragePolicy(retentionDays = 30), now)

        assertFalse(old.exists())
        assertTrue(current.exists())
        assertEquals(1, plan.logs.size)
        val content = current.readText()
        assertTrue("content=$content", content.contains("\"type\":\"retention_trimmed\""))
    }

    @Test
    fun `лимит объёма удаляет старый файл, сегодняшний защищён`() {
        val old = file("logs/events-2026-02-01.jsonl", 100)
        val current = file("logs/events-2026-03-01.jsonl", 40)

        val plan = log.trimOld(StoragePolicy(maxBytes = 50), now)

        assertFalse(old.exists())
        assertTrue(current.exists())
        assertEquals(1, plan.logs.size)
    }

    @Test
    fun `план экспорта учитывает диапазон и пустой период`() {
        file("logs/events-2026-01-01.jsonl", 10)
        file("logs/events-2026-02-25.jsonl", 20)
        file("logs/events-2026-03-01.jsonl", 30)

        val plan = log.planExport(ExportRange.DAYS_30, now)

        assertEquals(2, plan.files.size)
        assertEquals(50L, plan.totalBytes)
        assertFalse(plan.isEmpty)

        val empty = log.planExport(ExportRange.DAYS_7, StorageDates.startOfDayMs(LocalDate.of(2026, 6, 1)))
        assertTrue(empty.isEmpty)
    }

    private fun file(relative: String, size: Int): File =
        File(root, relative).apply {
            parentFile?.mkdirs()
            writeText("x".repeat(size))
        }
}
