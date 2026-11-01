package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackIdentityTest {

    private fun snap(
        title: String? = "Song",
        artist: String? = "Artist",
        durationMs: Long? = 180_000L,
        mediaId: String? = "id",
        source: String = StockPlayerSources.MEDIA_SESSION,
        packageName: String = "com.example.player",
    ) = MediaSnapshot(
        packageName = packageName,
        title = title,
        artist = artist,
        durationMs = durationMs,
        mediaId = mediaId,
        source = source,
    )

    @Test
    fun `ключ не зависит от длительности mediaId и источника`() {
        val first = snap(durationMs = null, mediaId = null, source = StockPlayerSources.NOTIFICATION)
        val second = snap(
            durationMs = 200_000,
            mediaId = "path",
            source = StockPlayerSources.FYT_BROADCAST,
        )

        assertEquals(first.trackKey, second.trackKey)
        assertTrue(first.sameTrack(second))
    }

    @Test
    fun `ключ нормализует регистр и пробелы`() {
        val first = snap(title = "  Song   Title ", artist = "ARTIST")
        val second = snap(title = "song title", artist = " artist ")

        assertEquals(first.trackKey, second.trackKey)
        assertTrue(first.sameTrack(second))
    }

    @Test
    fun `поздний исполнитель считается обогащением`() {
        val withoutArtist = snap(artist = null)
        val withArtist = snap(artist = "Artist")

        assertEquals("com.example.player::song::", withoutArtist.trackKey)
        assertTrue(withoutArtist.sameTrack(withArtist))
        assertTrue(withArtist.sameTrack(withoutArtist))
    }

    @Test
    fun `другой исполнитель или другой пакет это другой трек`() {
        assertFalse(snap(artist = "Artist").sameTrack(snap(artist = "Other")))
        assertFalse(
            snap(packageName = "com.example.player")
                .sameTrack(snap(packageName = "com.other.player")),
        )
        assertFalse(snap(title = "Song").sameTrack(snap(title = "Other Song")))
    }
}
