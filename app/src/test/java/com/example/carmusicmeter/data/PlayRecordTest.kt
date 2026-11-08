package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayRecordTest {

    @Test
    fun `track_ended становится фактом`() {
        val play = PlayRecord.fromTrackEnded(
            event = endedEvent(),
            bootId = "b1",
            deviceId = "dev",
            appVersion = "1.0",
            clockVerified = true,
        )

        requireNotNull(play)
        assertEquals("p1", play.playId)
        assertEquals("live", play.origin)
        assertEquals("com.p::track::artist", play.trackKey)
        assertEquals(1_000L, play.startedAt)
        assertEquals(2_000L, play.endedAt)
        assertEquals(900L, play.playedMs)
        assertEquals("skipped", play.endReason)
        assertEquals(43, play.volumeMax)
        assertEquals(1, play.seekBack)
        assertEquals(2, play.seekForward)
        assertEquals(12, play.volumeStats["mcu"]?.end)
        assertEquals(false, play.open)
    }

    @Test
    fun `факт переживает JSON-круговорот`() {
        val play = playRecord(playId = "abc", endReason = "completed", playedMs = 180_000L)

        val restored = PlayRecord.fromJson(play.toJsonLine())

        assertEquals(play, restored)
    }

    @Test
    fun `CSV экранирует запятые и кавычки`() {
        val play = playRecord().copy(title = "A, B \"C\"")
        val row = PlayCsv.row(play)
        val csv = PlayCsv.render(listOf(play))

        assertEquals("A, B \"C\"", row[PlayCsv.header.indexOf("title")])
        assertTrue(csv.contains("\"A, B \"\"C\"\"\""))
        assertTrue(csv.startsWith("playsSchemaVersion,playId,origin"))
        assertTrue(csv.endsWith("\r\n"))
    }

    @Test
    fun `CSV разворачивает сегменты громкости`() {
        val row = PlayCsv.row(playRecord())
        assertEquals("10", row[PlayCsv.header.indexOf("mcuStart")])
        assertEquals("43", row[PlayCsv.header.indexOf("mcuMax")])
        assertEquals("", row[PlayCsv.header.indexOf("audioStart")])
    }

    @Test
    fun `событие не track_ended не становится фактом`() {
        assertNull(
            PlayRecord.fromTrackEnded(
                event = LogEvent(ts = 1, type = "track_started"),
                bootId = "b",
                deviceId = "d",
                appVersion = "1",
                clockVerified = true,
            ),
        )
    }

    @Test
    fun `открытое прослушивание становится open-фактом`() {
        val open = OpenPlay(
            bootId = "b1",
            trackKey = "k",
            packageName = "com.p",
            source = "media_session",
            title = "T",
            artist = "A",
            album = null,
            mediaId = null,
            durationMs = 180_000L,
            positionMs = 45_000L,
            playedMs = 40_000L,
            startedAtWallMs = 1_000L,
            lastTsWallMs = 1_500L,
            volumeStart = 10,
            volumeLow = 10,
            volumeHigh = 12,
            volumeEnd = 12,
            volumeMax = 43,
            volumeChanges = 1,
            volumeSource = "mcu",
            volumeStats = mapOf("mcu" to OpenPlayVolume(10, 10, 12, 12, 43, 1)),
            playId = "open-1",
            seekBack = 1,
            seekForward = 2,
        )

        val play = PlayRecord.fromOpenPlay(open, "dev", "1.0", true)

        assertEquals("open-1", play.playId)
        assertEquals(true, play.open)
        assertEquals("open", play.endReason)
        assertEquals(45_000L, play.endPositionMs)
        assertEquals(1, play.seekBack)
        assertEquals(2, play.seekForward)
        assertEquals(12, play.volumeStats["mcu"]?.end)
    }
}
