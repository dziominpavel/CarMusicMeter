package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayBuilderTest {

    @Test
    fun `закрытый трек становится одним фактом`() {
        val result = build(
            listOf(
                eventLine(1_000, 100, "track_started", fields = startFields("k", "p1")),
                eventLine(2_000, 200, "track_ended", fields = endFields("k", "p1", 1_000, 900, "skipped")),
            ),
        )

        assertEquals(1, result.plays.size)
        val play = result.plays.single()
        assertEquals("p1", play.playId)
        assertEquals("history", play.origin)
        assertEquals(1_000L, play.startedAt)
        assertEquals(2_000L, play.endedAt)
        assertEquals(900L, play.playedMs)
        assertEquals("skipped", play.endReason)
        assertEquals(false, play.open)
    }

    @Test
    fun `двойное закрытие схлопывается в одно прослушивание`() {
        val result = build(
            listOf(
                eventLine(1_000, 100, "track_started", fields = startFields("k", "p1")),
                eventLine(2_000, 200, "track_ended", fields = endFields("k", "p1", 1_000, 900, "session_lost")),
                eventLine(2_001, 201, "track_ended", fields = endFields("k", "p1", 1_000, 900, "session_lost") + mapOf("restored" to true)),
            ),
        )

        assertEquals(1, result.plays.size)
        assertEquals(false, result.plays.single().restored)
        assertEquals(2_000L, result.plays.single().endedAt)
    }

    @Test
    fun `скачок часов переносит старт и конец`() {
        val result = build(
            listOf(
                eventLine(1_000, 100, "track_started", fields = startFields("k", "p1")),
                eventLine(2_000, 200, "track_ended", fields = endFields("k", "p1", 1_000, 900, "skipped")),
                eventLine(5_000, 300, "clock_changed", fields = mapOf("wallDelta" to 10_000L)),
            ),
        )

        assertEquals(11_000L, result.plays.single().startedAt)
        assertEquals(12_000L, result.plays.single().endedAt)
    }

    @Test
    fun `completed и перемотки попадают в факт`() {
        val result = build(
            listOf(
                eventLine(1_000, 100, "track_started", fields = startFields("k", "p1")),
                eventLine(1_200, 120, "seek", fields = mapOf("trackKey" to "k", "deltaMs" to -700L)),
                eventLine(1_400, 140, "seek", fields = mapOf("trackKey" to "k", "deltaMs" to 900L)),
                eventLine(2_000, 200, "track_ended", fields = endFields("k", "p1", 1_000, 1_000, "completed")),
            ),
        )

        val play = result.plays.single()
        assertTrue(play.completed)
        assertEquals(1, play.seekBack)
        assertEquals(1, play.seekForward)
    }

    @Test
    fun `агрегаты громкости восстанавливаются из volume_changed`() {
        val result = build(
            listOf(
                eventLine(1_000, 100, "track_started", fields = startFields("k", "p1")),
                eventLine(
                    5_000,
                    140,
                    "volume_changed",
                    fields = mapOf(
                        "trackKey" to "k",
                        "source" to "mcu",
                        "previous" to 10,
                        "value" to 2,
                        "delta" to -8,
                    ),
                ),
                eventLine(
                    9_000,
                    180,
                    "volume_changed",
                    fields = mapOf(
                        "trackKey" to "k",
                        "source" to "mcu",
                        "previous" to 2,
                        "value" to 7,
                        "delta" to 5,
                    ),
                ),
                eventLine(20_000, 200, "track_ended", fields = endFields("k", "p1", 1_000, 19_000, "skipped")),
            ),
        )

        val mcu = result.plays.single().volumeStats.getValue("mcu")
        assertEquals(5, mcu.up)
        assertEquals(8, mcu.down)
        assertEquals(7, mcu.level)
        assertEquals(4_000L, mcu.dropMs)
        assertEquals(8_000L, mcu.raiseMs)
    }

    @Test
    fun `незакрытый трек остаётся открытым`() {
        val result = build(
            listOf(
                eventLine(3_000, 300, "track_started", fields = startFields("k2", "p2")),
                eventLine(
                    3_500,
                    350,
                    "track_progress",
                    fields = mapOf(
                        "trackKey" to "k2",
                        "startedAt" to 3_000L,
                        "playedMs" to 500L,
                        "positionMs" to 500L,
                        "volume" to 10,
                        "volumeMax" to 43,
                        "volumeSource" to "mcu",
                    ),
                ),
            ),
        )

        val play = result.plays.single()
        assertEquals(true, play.open)
        assertEquals("open", play.endReason)
        assertEquals(500L, play.playedMs)
        assertEquals(500L, play.endPositionMs)
        assertEquals(3_500L, play.endedAt)
    }

    @Test
    fun `непроверенные часы помечаются`() {
        val result = build(
            listOf(
                eventLine(1_000, 100, "track_started", verified = false, fields = startFields("k", "p1")),
                eventLine(2_000, 200, "track_ended", verified = false, fields = endFields("k", "p1", 1_000, 900, "skipped")),
            ),
        )

        assertEquals(false, result.plays.single().clockVerified)
    }

    @Test
    fun `битая строка не роняет разбор`() {
        val result = build(
            listOf(
                "not json",
                eventLine(1_000, 100, "track_started", fields = startFields("k", "p1")),
                eventLine(2_000, 200, "track_ended", fields = endFields("k", "p1", 1_000, 900, "skipped")),
            ),
        )

        assertEquals(1, result.malformed)
        assertTrue(result.plays.isNotEmpty())
    }

    private fun build(lines: List<String>): PlayBuilder.Result =
        PlayBuilder.build(lines.asSequence(), "dev", "1.0", 1_800_000_000_000L)
}
