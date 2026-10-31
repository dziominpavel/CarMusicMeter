package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockPlayerParserTest {

    private val now = 1_793_470_000_000L

    @Test
    fun `broadcast разбирает название исполнителя путь и состояние`() {
        val state = StockPlayerParser.fromBroadcast(
            extras = mapOf(
                "title" to "Late In July",
                "play_artist" to "ToTheMoon",
                "play_album" to "Late In July",
                "play_path" to "/storage/usb/music/late.mp3",
                "play_state" to true,
                "play_total" to 193_720L,
                "play_cur" to 12_000L,
            ),
            nowMs = now,
            packageName = "com.yf.teyesspotify",
        )

        assertEquals("Late In July", state?.title)
        assertEquals("ToTheMoon", state?.artist)
        assertEquals(193_720L, state?.durationMs)
        assertEquals(12_000L, state?.positionMs)
        assertEquals(StockPlayerSources.FYT_BROADCAST, state?.source)
        assertEquals(MediaPhase.PLAYING, state?.toSnapshot(now, isMusicActive = true)?.phase)
    }

    @Test
    fun `broadcast игнорирует пустой и Unknown`() {
        assertNull(
            StockPlayerParser.fromBroadcast(
                mapOf("title" to "Unknown", "play_state" to true),
                now,
                "com.yf.teyesspotify",
            ),
        )
        assertNull(
            StockPlayerParser.fromBroadcast(
                mapOf("play_state" to true),
                now,
                "com.yf.teyesspotify",
            ),
        )
    }

    @Test
    fun `accessibility отсекает кнопки время и выбирает название`() {
        val state = StockPlayerParser.fromAccessibility(
            packageName = "com.yf.teyesspotify",
            texts = listOf("play", "pause", "Song Title", "Artist Name", "03:21", "100%"),
            playing = true,
            nowMs = now,
        )

        assertEquals("Song Title", state?.title)
        assertEquals("Artist Name", state?.artist)
        assertEquals(StockPlayerSources.ACCESSIBILITY, state?.source)
        assertTrue(state?.rawTexts?.contains("03:21") == false)
    }

    @Test
    fun `accessibility разбирает строку исполнитель dash название`() {
        val state = StockPlayerParser.fromAccessibility(
            packageName = "com.yf.teyesspotify",
            texts = listOf("Artist Name - Song Title"),
            playing = true,
            nowMs = now,
        )

        assertEquals("Song Title", state?.title)
        assertEquals("Artist Name", state?.artist)
    }

    @Test
    fun `notification использует remote texts если extras пустые`() {
        val state = StockPlayerParser.fromNotification(
            packageName = "com.yf.teyesspotify",
            title = null,
            text = null,
            remoteTexts = listOf("Song Title", "Artist Name"),
            nowMs = now,
        )

        assertEquals("Song Title", state?.title)
        assertEquals("Artist Name", state?.artist)
        assertEquals(StockPlayerSources.NOTIFICATION, state?.source)
    }

    @Test
    fun `bridge не даёт accessibility перебить свежий broadcast`() {
        StockPlayerBridge.reset()
        val broadcast = StockPlayerParser.fromBroadcast(
            mapOf("title" to "Broadcast Song", "play_artist" to "A", "play_state" to true),
            now,
            "com.yf.teyesspotify",
        )!!
        StockPlayerBridge.update(broadcast)

        val accessibility = StockPlayerParser.fromAccessibility(
            packageName = "com.yf.teyesspotify",
            texts = listOf("Other Song", "B"),
            playing = true,
            nowMs = now + 2_000L,
        )!!
        assertTrue(!StockPlayerBridge.update(accessibility))
        assertEquals("Broadcast Song", StockPlayerBridge.snapshot(now + 2_000L, true)?.title)

        val later = StockPlayerParser.fromAccessibility(
            packageName = "com.yf.teyesspotify",
            texts = listOf("Other Song", "B"),
            playing = true,
            nowMs = now + 11_000L,
        )!!
        assertTrue(StockPlayerBridge.update(later))
        assertEquals("Other Song", StockPlayerBridge.snapshot(now + 11_000L, true)?.title)
        StockPlayerBridge.reset()
    }
}
