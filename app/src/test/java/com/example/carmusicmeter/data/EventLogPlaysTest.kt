package com.example.carmusicmeter.data

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventLogPlaysTest {

    private val root: File = Files.createTempDirectory("cmm-plays").toFile()
    private val today = LocalDate.of(2026, 11, 7)
    private val now = StorageDates.startOfDayMs(today) + 12L * 60L * 60L * 1000L
    private val log = EventLog(root, bootId = "boot", monoClock = { 1L }, clockVerified = { true })

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun `закрытие пишет событие и факт`() {
        val ts = StorageDates.startOfDayMs(LocalDate.of(2026, 11, 6)) + 12L * 60L * 60L * 1000L
        val play = playRecord(startedAt = ts, endedAt = ts + 1_000L)
        val event = endedEvent(startedAt = ts, endedAt = ts + 1_000L)

        log.appendClose(event, play)

        val file = log.playFileFor(play)
        assertTrue(file.isFile)
        val restored = PlayRecord.fromJson(file.readLines().single())
        assertEquals(play.playId, restored?.playId)
        assertEquals(play.playedMs, restored?.playedMs)
        assertTrue(log.todayFile(ts + 1_000L).isFile)
    }

    @Test
    fun `открытый факт пишется и удаляется`() {
        val open = playRecord(playId = "o1", open = true)

        log.writeOpenPlay(open)
        val file = File(root, "plays/open.json")
        assertTrue(file.isFile)
        assertEquals("o1", PlayRecord.fromJson(file.readText())?.playId)

        log.writeOpenPlay(null)
        assertFalse(file.exists())
    }

    @Test
    fun `статистика учитывает факты`() {
        val ts = now
        log.appendPlays(listOf(playRecord(startedAt = ts, endedAt = ts + 1_000L)))

        val stats = log.storageStats()

        assertEquals(1, stats.playFiles)
        assertTrue(stats.playBytes > 0)
        assertTrue(stats.totalBytes >= stats.playBytes)
    }

    @Test
    fun `чистка удаляет факты и кэш старого дня`() {
        val old = LocalDate.of(2026, 11, 1)
        val oldLog = File(log.logsDir, "events-$old.jsonl").apply { writeText("x".repeat(10)) }
        val oldPlay = File(root, "plays/plays-$old.jsonl").apply {
            parentFile?.mkdirs()
            writeText("play")
        }
        val oldCache = File(root, "report/cache/v1/plays-$old.jsonl").apply {
            parentFile?.mkdirs()
            writeText("cache")
        }
        val current = File(log.logsDir, "events-$today.jsonl").apply { writeText("x") }

        log.trimOld(StoragePolicy(retentionDays = 1), now)

        assertFalse(oldLog.exists())
        assertFalse(oldPlay.exists())
        assertFalse(oldCache.exists())
        assertTrue(current.exists())
    }
}
