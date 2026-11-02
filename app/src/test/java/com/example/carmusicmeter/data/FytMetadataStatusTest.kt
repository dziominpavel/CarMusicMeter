package com.example.carmusicmeter.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FytMetadataStatusTest {

    @After
    fun tearDown() {
        FytMetadataStatus.reset()
    }

    @Test
    fun `первый пакет и смена ключей дают событие`() {
        val first = FytMetadataStatus.update(
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
            keys = setOf("title", "play_artist"),
            title = "Song",
            artist = "Artist",
            playing = true,
            nowMs = 1_000L,
        )
        val same = FytMetadataStatus.update(
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
            keys = setOf("play_artist", "title"),
            title = "Song 2",
            artist = "Artist",
            playing = true,
            nowMs = 2_000L,
        )
        val changed = FytMetadataStatus.update(
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
            keys = setOf("title", "play_artist", "play_total"),
            title = "Song 2",
            artist = "Artist",
            playing = true,
            nowMs = 3_000L,
        )

        assertTrue(first)
        assertFalse(same)
        assertTrue(changed)
    }

    @Test
    fun `снимок содержит package keys и updatedAt`() {
        FytMetadataStatus.update(
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
            keys = setOf("title", "play_artist", "play_total"),
            title = "Song",
            artist = "Artist",
            playing = true,
            nowMs = 5_000L,
        )

        val json = FytMetadataStatus.toJson()

        assertTrue(json.getBoolean("received"))
        assertEquals(StockPlayerProtocol.STOCK_PACKAGE, json.getString("package"))
        assertEquals(3, json.getJSONArray("keys").length())
        assertEquals(5_000L, json.getLong("updatedAt"))
        assertEquals("Song", json.getString("title"))

        val event = FytMetadataStatus.toLogEvent(5_000L)
        assertEquals("fyt_metadata_service", event.type)
        assertEquals(true, event.fields["received"])
        assertEquals(StockPlayerProtocol.STOCK_PACKAGE, event.fields["package"])
        assertEquals("Song", event.fields["title"])
    }
}
