package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `broadcast из FYT-сервиса помечается fyt_service`() {
        val state = StockPlayerParser.fromBroadcast(
            extras = mapOf(
                "title" to "Service Song",
                "play_artist" to "Artist",
                "play_state" to true,
            ),
            nowMs = now,
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
            source = StockPlayerSources.FYT_SERVICE,
        )

        assertEquals("Service Song", state?.title)
        assertEquals(StockPlayerSources.FYT_SERVICE, state?.source)
        assertEquals(StockPlayerProtocol.STOCK_PACKAGE, state?.packageName)
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
    fun `broadcast не принимает служебное Неизвестно`() {
        assertNull(
            StockPlayerParser.fromBroadcast(
                mapOf("title" to "Неизвестно", "play_state" to true),
                now,
                "com.syu.music",
                source = StockPlayerSources.FYT_SERVICE,
            ),
        )
    }

    @Test
    fun `broadcast очищает заглушки в исполнителе и альбоме`() {
        val state = StockPlayerParser.fromBroadcast(
            mapOf(
                "title" to "Real Song",
                "play_artist" to "Неизвестно",
                "play_album" to "Unknown",
                "play_state" to true,
            ),
            now,
            "com.syu.music",
            source = StockPlayerSources.FYT_SERVICE,
        )

        assertEquals("Real Song", state?.title)
        assertNull(state?.artist)
        assertNull(state?.album)
    }

    @Test
    fun `accessibility не принимает служебное Неизвестно`() {
        val result = StockPlayerParser.inspectAccessibility(
            packageName = "com.syu.music",
            window = window("Неизвестно", "Неизвестно"),
            playing = true,
            nowMs = now,
        )

        assertFalse(result.accepted)
        assertNull(result.state)
    }

    @Test
    fun `broadcast без play state не считается паузой при активной музыке`() {
        val state = StockPlayerParser.fromBroadcast(
            extras = mapOf("title" to "Song Title"),
            nowMs = now,
            packageName = "com.yf.teyesspotify",
        )

        assertTrue(state != null)
        assertTrue(state?.playingKnown == false)
        assertEquals(MediaPhase.PLAYING, state?.toSnapshot(now, isMusicActive = true)?.phase)
        assertEquals(MediaPhase.PAUSED, state?.toSnapshot(now, isMusicActive = false)?.phase)
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
    fun `bridge не даёт accessibility перебить свежий fyt_service`() {
        StockPlayerBridge.reset()
        val service = StockPlayerParser.fromBroadcast(
            extras = mapOf("title" to "Service Song", "play_artist" to "A", "play_state" to true),
            nowMs = now,
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
            source = StockPlayerSources.FYT_SERVICE,
        )!!
        StockPlayerBridge.update(service)

        val accessibility = StockPlayerParser.fromAccessibility(
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
            texts = listOf("Other Song", "B"),
            playing = true,
            nowMs = now + 2_000L,
        )!!
        assertTrue(!StockPlayerBridge.update(accessibility))
        assertEquals("Service Song", StockPlayerBridge.snapshot(now + 2_000L, true)?.title)

        val later = StockPlayerParser.fromAccessibility(
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
            texts = listOf("Other Song", "B"),
            playing = true,
            nowMs = now + 11_000L,
        )!!
        assertTrue(StockPlayerBridge.update(later))
        assertEquals("Other Song", StockPlayerBridge.snapshot(now + 11_000L, true)?.title)
        StockPlayerBridge.reset()
    }

    @Test
    fun `bridge сохраняет path и duration на переходном intent`() {
        StockPlayerBridge.reset()
        val first = StockPlayerParser.fromBroadcast(
            extras = mapOf(
                "title" to "Song A",
                "play_artist" to "Artist",
                "play_path" to "/music/a.flac",
                "play_state" to true,
                "play_total" to 200_000L,
            ),
            nowMs = now,
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
            source = StockPlayerSources.FYT_SERVICE,
        )!!
        StockPlayerBridge.update(first)

        val transition = StockPlayerParser.fromBroadcast(
            extras = mapOf(
                "title" to "Song A",
                "play_artist" to "Artist",
                "play_path" to "/music/b.flac",
                "play_state" to false,
            ),
            nowMs = now + 1_000L,
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
            source = StockPlayerSources.FYT_SERVICE,
        )!!
        assertTrue(StockPlayerBridge.update(transition))

        val snapshot = StockPlayerBridge.snapshot(now + 1_000L, true)!!
        assertEquals("/music/a.flac", snapshot.mediaId)
        assertEquals(200_000L, snapshot.durationMs)
        assertEquals(MediaPhase.PAUSED, snapshot.phase)
        StockPlayerBridge.reset()
    }

    @Test
    fun `bridge принимает новый трек после переходного intent`() {
        StockPlayerBridge.reset()
        val first = StockPlayerParser.fromBroadcast(
            extras = mapOf(
                "title" to "Song A",
                "play_artist" to "Artist",
                "play_path" to "/music/a.flac",
                "play_state" to true,
                "play_total" to 200_000L,
            ),
            nowMs = now,
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
            source = StockPlayerSources.FYT_SERVICE,
        )!!
        StockPlayerBridge.update(first)

        val transition = StockPlayerParser.fromBroadcast(
            extras = mapOf(
                "title" to "Song A",
                "play_artist" to "Artist",
                "play_path" to "/music/b.flac",
                "play_state" to false,
            ),
            nowMs = now + 1_000L,
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
            source = StockPlayerSources.FYT_SERVICE,
        )!!
        StockPlayerBridge.update(transition)

        val next = StockPlayerParser.fromBroadcast(
            extras = mapOf(
                "title" to "Song B",
                "play_artist" to "Artist",
                "play_path" to "/music/b.flac",
                "play_state" to true,
                "play_total" to 180_000L,
            ),
            nowMs = now + 2_000L,
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
            source = StockPlayerSources.FYT_SERVICE,
        )!!
        assertTrue(StockPlayerBridge.update(next))

        val snapshot = StockPlayerBridge.snapshot(now + 2_000L, true)!!
        assertEquals("Song B", snapshot.title)
        assertEquals("/music/b.flac", snapshot.mediaId)
        assertEquals(180_000L, snapshot.durationMs)
        StockPlayerBridge.reset()
    }

    @Test
    fun `bridge touch не продлевает fyt_service`() {
        StockPlayerBridge.reset()
        val service = StockPlayerParser.fromBroadcast(
            extras = mapOf(
                "title" to "Service Song",
                "play_artist" to "Artist",
                "play_path" to "/music/service.flac",
                "play_state" to true,
                "play_total" to 200_000L,
            ),
            nowMs = now,
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
            source = StockPlayerSources.FYT_SERVICE,
        )!!
        StockPlayerBridge.update(service)

        assertFalse(StockPlayerBridge.touch(now + 20_000L))
        assertNull(StockPlayerBridge.snapshot(now + 31_000L, true))
        StockPlayerBridge.reset()
    }

    @Test
    fun `bridge touch продлевает accessibility`() {
        StockPlayerBridge.reset()
        val accessibility = StockPlayerParser.fromAccessibility(
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
            texts = listOf("Accessibility Song", "Artist"),
            playing = true,
            nowMs = now,
        )!!
        StockPlayerBridge.update(accessibility)

        assertTrue(StockPlayerBridge.touch(now + 20_000L))
        assertNotNull(StockPlayerBridge.snapshot(now + 20_000L, true))
        StockPlayerBridge.reset()
    }

    @Test
    fun `bridge принимает fyt_service поверх broadcast`() {
        StockPlayerBridge.reset()
        val broadcast = StockPlayerParser.fromBroadcast(
            extras = mapOf("title" to "Broadcast Song", "play_state" to true),
            nowMs = now,
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
        )!!
        StockPlayerBridge.update(broadcast)

        val service = StockPlayerParser.fromBroadcast(
            extras = mapOf("title" to "Service Song", "play_state" to true),
            nowMs = now + 1_000L,
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
            source = StockPlayerSources.FYT_SERVICE,
        )!!
        assertTrue(StockPlayerBridge.update(service))
        assertEquals(
            StockPlayerSources.FYT_SERVICE,
            StockPlayerBridge.snapshot(now + 1_000L, true)?.source,
        )
        StockPlayerBridge.reset()
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

    private fun window(vararg texts: String, rate: AccessibilityRange? = null): AccessibilityWindow =
        AccessibilityWindow(
            packageName = "com.syu.music",
            nodes = texts.map { AccessibilityNode(text = it) } +
                listOfNotNull(rate?.let { AccessibilityNode(range = it) }),
        )

    @Test
    fun `accessibility отклоняет заголовок папки и строку списка`() {
        val result = StockPlayerParser.inspectAccessibility(
            packageName = "com.syu.music",
            window = window(
                "Список",
                "1763. Tina Turner - The Best.flac",
                "1764. Tinie Tempah - Written In The Stars.flac",
            ),
            playing = true,
            nowMs = now,
        )

        assertFalse(result.accepted)
        assertEquals("folder_header", result.rejectReason)
        assertNull(result.state)
    }

    @Test
    fun `accessibility принимает строку исполнитель тире название`() {
        val result = StockPlayerParser.inspectAccessibility(
            packageName = "com.syu.music",
            window = window(
                "Tina Turner - The Best.flac",
                "Список",
                "Tina Turner",
                "1763. Tina Turner - The Best.flac",
            ),
            playing = true,
            nowMs = now,
        )

        assertTrue(result.accepted)
        assertEquals("The Best", result.state?.title)
        assertEquals("Tina Turner", result.state?.artist)
        assertEquals("high", result.confidence)
    }

    @Test
    fun `accessibility не принимает экран папки за трек`() {
        val result = StockPlayerParser.inspectAccessibility(
            packageName = "com.syu.music",
            window = window("Внутренняя память", "Список музыки"),
            playing = true,
            nowMs = now,
        )

        assertFalse(result.accepted)
        assertEquals("folder_header", result.rejectReason)
    }

    @Test
    fun `accessibility снимает медиа расширение`() {
        val state = StockPlayerParser.fromAccessibility(
            packageName = "com.syu.music",
            texts = listOf("The Best.flac", "Tina Turner"),
            playing = true,
            nowMs = now,
        )

        assertEquals("The Best", state?.title)
    }

    @Test
    fun `время задает позицию и длительность`() {
        val result = StockPlayerParser.inspectAccessibility(
            packageName = "com.syu.music",
            window = window("The Best.flac", "Tina Turner", "00:30", "03:45"),
            playing = true,
            nowMs = now,
        )

        assertEquals(30_000L, result.state?.positionMs)
        assertEquals(225_000L, result.state?.durationMs)
    }

    @Test
    fun `одно время задает только позицию`() {
        val result = StockPlayerParser.inspectAccessibility(
            packageName = "com.syu.music",
            window = window("The Best.flac", "Tina Turner", "00:30"),
            playing = true,
            nowMs = now,
        )

        assertEquals(30_000L, result.state?.positionMs)
        assertNull(result.state?.durationMs)
    }

    @Test
    fun `rangeInfo в миллисекундах задает прогресс`() {
        val result = StockPlayerParser.inspectAccessibility(
            packageName = "com.syu.music",
            window = window(
                "The Best.flac",
                "Tina Turner",
                rate = AccessibilityRange(min = 0f, max = 180_000f, current = 30_000f),
            ),
            playing = true,
            nowMs = now,
        )

        assertEquals(30_000L, result.state?.positionMs)
        assertEquals(180_000L, result.state?.durationMs)
    }

    @Test
    fun `rangeInfo в долях без времени не подставляет длительность`() {
        val result = StockPlayerParser.inspectAccessibility(
            packageName = "com.syu.music",
            window = window(
                "The Best.flac",
                "Tina Turner",
                rate = AccessibilityRange(min = 0f, max = 1f, current = 0.5f),
            ),
            playing = true,
            nowMs = now,
        )

        assertNull(result.state?.positionMs)
        assertNull(result.state?.durationMs)
    }
}
