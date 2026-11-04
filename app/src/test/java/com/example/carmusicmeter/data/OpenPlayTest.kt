package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenPlayTest {

    private val sample = OpenPlay(
        bootId = "a1b2c3d4",
        trackKey = "com.syu.music::the best::tina turner",
        packageName = "com.syu.music",
        source = StockPlayerSources.FYT_SERVICE,
        title = "The Best",
        artist = "Tina Turner",
        album = "Simply the Best",
        mediaId = "/storage/USB1/Music/Tina Turner - The Best.flac",
        durationMs = 180_000L,
        positionMs = 45_000L,
        playedMs = 40_000L,
        startedAtWallMs = 1_700_000_000_000L,
        lastTsWallMs = 1_700_000_040_000L,
        volumeStart = 4,
        volumeLow = 4,
        volumeHigh = 12,
        volumeEnd = 12,
        volumeMax = 43,
        volumeChanges = 5,
        volumeSource = "mcu",
        volumeStats = mapOf(
            "mcu" to OpenPlayVolume(start = 4, low = 4, high = 12, end = 12, max = 43, changes = 5),
            "audio" to OpenPlayVolume(start = 25, low = 25, high = 25, end = 25, max = 25, changes = 0),
        ),
    )

    @Test
    fun `сохранение и чтение открытого прослушивания`() {
        val restored = OpenPlay.fromJson(sample.toJson())

        assertEquals(sample, restored)
    }

    @Test
    fun `битая запись не роняет приложение`() {
        assertNull(OpenPlay.fromJson("{не json"))
        assertNull(OpenPlay.fromJson(""))
        assertNull(OpenPlay.fromJson(null))
    }

    @Test
    fun `отсутствующие поля читаются как пустые`() {
        val restored = OpenPlay.fromJson(
            """{"bootId":"b","trackKey":"k"}""",
        )

        assertEquals("b", restored?.bootId)
        assertEquals("k", restored?.trackKey)
        assertNull(restored?.title)
        assertEquals(0L, restored?.playedMs)
        assertTrue(restored?.volumeStats?.isEmpty() == true)
    }
}
