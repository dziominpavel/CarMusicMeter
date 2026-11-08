package com.example.carmusicmeter.data

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaySourceTest {

    private val root: File = Files.createTempDirectory("cmm-source").toFile()

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun `история строится из журнала`() {
        writeLog(
            "events-2026-11-06.jsonl",
            eventLine(1_000, 100, "track_started", fields = startFields("k", "h1")),
            eventLine(2_000, 200, "track_ended", fields = endFields("k", "h1", 1_000, 900, "skipped")),
        )

        val source = PlaySource(root, "dev", "1.0", liveSince = null)
        val plays = source.load()

        assertEquals(1, plays.size)
        assertEquals("history", plays.single().origin)
        assertEquals(1, source.diagnostics().history)
        assertEquals(1, source.diagnostics().cacheDays)
    }

    @Test
    fun `live и история сливаются без дублей`() {
        writeLog(
            "events-2026-11-06.jsonl",
            eventLine(1_000, 100, "track_started", fields = startFields("k", "h1")),
            eventLine(2_000, 200, "track_ended", fields = endFields("k", "h1", 1_000, 900, "skipped")),
        )
        writeLive(playRecord(playId = "l1", trackKey = "l", startedAt = 3_000, endedAt = 4_000))

        val source = PlaySource(root, "dev", "1.0", liveSince = 3_000)
        val plays = source.load()

        assertEquals(2, plays.size)
        assertEquals(1, source.diagnostics().live)
        assertEquals(1, source.diagnostics().history)
    }

    @Test
    fun `пересечение с live отбрасывает историю`() {
        writeLog(
            "events-2026-11-06.jsonl",
            eventLine(1_000, 100, "track_started", fields = startFields("same", "h1")),
            eventLine(3_000, 300, "track_ended", fields = endFields("same", "h1", 1_000, 2_000, "stopped")),
        )
        writeLive(
            playRecord(
                playId = "l1",
                trackKey = "same",
                startedAt = 2_000,
                endedAt = 4_000,
            ),
        )

        val source = PlaySource(root, "dev", "1.0", liveSince = 2_000)
        val plays = source.load()

        assertEquals(1, plays.size)
        assertEquals("l1", plays.single().playId)
        assertEquals(1, source.diagnostics().overlaps)
    }

    @Test
    fun `диапазон фильтрует закрытые факты`() {
        writeLog(
            "events-2026-11-06.jsonl",
            eventLine(1_000, 100, "track_started", fields = startFields("k", "h1")),
            eventLine(2_000, 200, "track_ended", fields = endFields("k", "h1", 1_000, 900, "skipped")),
        )
        writeLive(playRecord(playId = "l1", trackKey = "l", startedAt = 3_000, endedAt = 4_000))

        val plays = PlaySource(root, "dev", "1.0", liveSince = 3_000).load(2_500, 3_500)

        assertEquals(1, plays.size)
        assertEquals("l1", plays.single().playId)
    }

    @Test
    fun `открытый факт возвращается всегда`() {
        writeOpen(playRecord(playId = "o1", startedAt = 5_000, endedAt = 5_500, open = true))

        val source = PlaySource(root, "dev", "1.0", liveSince = 5_000)
        val plays = source.load(0, 10)

        assertEquals(1, plays.size)
        assertTrue(plays.single().open)
        assertEquals(1, source.diagnostics().open)
    }

    @Test
    fun `старый день не разбирается при коротком периоде`() {
        writeLog(
            "events-2026-10-01.jsonl",
            eventLine(1_000, 100, "track_started", fields = startFields("old", "o1")),
            eventLine(2_000, 200, "track_ended", fields = endFields("old", "o1", 1_000, 900, "skipped")),
        )
        val newStart = StorageDates.startOfDayMs(LocalDate.of(2026, 11, 6)) +
            12L * 60L * 60L * 1000L
        writeLog(
            "events-2026-11-06.jsonl",
            eventLine(newStart, 300, "track_started", fields = startFields("new", "n1")),
            eventLine(
                newStart + 1_000L,
                400,
                "track_ended",
                fields = endFields("new", "n1", newStart, 900, "skipped"),
            ),
        )

        val from = StorageDates.startOfDayMs(LocalDate.of(2026, 11, 1))
        val plays = PlaySource(root, "dev", "1.0", liveSince = null).load(from, null)

        assertEquals(1, plays.size)
        assertEquals("n1", plays.single().playId)
    }

    @Test
    fun `непроверенные факты отделяются от основного периода`() {
        writeLog(
            "events-unverified.jsonl",
            eventLine(1_000, 100, "track_started", verified = false, fields = startFields("k", "u1")),
            eventLine(2_000, 200, "track_ended", verified = false, fields = endFields("k", "u1", 1_000, 900, "skipped")),
        )

        val source = PlaySource(root, "dev", "1.0", liveSince = null)
        val (dated, undated) = source.splitDated(source.load())

        assertEquals(0, dated.size)
        assertEquals(1, undated.size)
    }

    private fun writeLog(name: String, vararg lines: String) {
        val dir = File(root, "logs").apply { mkdirs() }
        File(dir, name).writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun writeLive(play: PlayRecord) {
        val dir = File(root, "plays").apply { mkdirs() }
        File(dir, "plays-2026-11-06.jsonl").appendText(play.toJsonLine() + "\n")
    }

    private fun writeOpen(play: PlayRecord) {
        val dir = File(root, "plays").apply { mkdirs() }
        File(dir, "open.json").writeText(play.toJsonLine())
    }
}
