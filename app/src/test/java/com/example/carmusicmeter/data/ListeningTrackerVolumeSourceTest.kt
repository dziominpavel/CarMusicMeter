package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningTrackerVolumeSourceTest {

    private fun snap(title: String = "A") = MediaSnapshot(
        packageName = "com.example.player",
        title = title,
        artist = "Artist",
        durationMs = 200_000L,
        positionMs = 0L,
        phase = MediaPhase.PLAYING,
        source = StockPlayerSources.FYT_BROADCAST,
    )

    @Test
    fun `смена источника не создаёт ложный delta и сбрасывает статистику`() {
        val tracker = ListeningTracker()
        tracker.onTick(0, snap(), 10, 30, source = "audio")
        tracker.onVolume(500, 12, 30, source = "audio")
        tracker.onVolume(1_000, 4, 0, source = "mcu")

        val events = tracker.onVolume(1_500, 5, 0, source = "mcu")

        assertEquals(1, events.size)
        assertEquals(5, events.first().fields["value"])
        assertNull(events.first().fields["max"])
        assertNull(events.first().fields["level"])
        assertEquals("mcu", events.first().fields["source"])
    }

    @Test
    fun `неизвестный максимум не превращается в ноль`() {
        val tracker = ListeningTracker()
        tracker.onVolume(0, 4, 0, source = "mcu")

        val events = tracker.onVolume(500, 5, 0, source = "mcu")

        assertEquals(1, events.size)
        assertNull(events.first().fields["max"])
        assertNull(events.first().fields["level"])
    }

    @Test
    fun `события трека содержат источник и источник громкости`() {
        val tracker = ListeningTracker()
        tracker.onVolume(0, 4, 0, source = "mcu")
        val events = tracker.onTick(0, snap(), 4, 0, source = "mcu").events

        val started = events.single { it.type == "track_started" }
        assertEquals(StockPlayerSources.FYT_BROADCAST, started.fields["source"])
        assertEquals("mcu", started.fields["volumeSource"])
        assertTrue(started.fields["volume"] == 4)
    }
}
