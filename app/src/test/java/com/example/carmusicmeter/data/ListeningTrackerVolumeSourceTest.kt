package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningTrackerVolumeSourceTest {

    private val baseWall = 1_700_000_000_000L

    private fun t(monoMs: Long, wallMs: Long = baseWall + monoMs) = TickTime(
        wallMs = wallMs,
        monoMs = monoMs,
    )

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
    fun `смена источника не создаёт ложный delta и пишет событие`() {
        val tracker = ListeningTracker()
        tracker.onTick(t(0), snap(), 10, 30, source = "audio")
        tracker.onVolume(t(500), 12, 30, source = "audio")

        val sourceEvent = tracker.onVolume(t(1_000), 4, 43, source = "mcu")
        assertEquals(1, sourceEvent.size)
        assertEquals("volume_source_changed", sourceEvent.first().type)
        assertEquals("audio", sourceEvent.first().fields["from"])
        assertEquals("mcu", sourceEvent.first().fields["to"])
        assertNull(sourceEvent.first().fields["delta"])

        val events = tracker.onVolume(t(1_500), 5, 43, source = "mcu")

        assertEquals(1, events.size)
        assertEquals(5, events.first().fields["value"])
        assertEquals(43, events.first().fields["max"])
        assertEquals(5.0 / 43.0, events.first().fields["level"])
        assertEquals("mcu", events.first().fields["source"])
    }

    @Test
    fun `статистика громкости не теряет MCU после возврата источника`() {
        val tracker = ListeningTracker()
        tracker.onVolume(t(0), 3, 43, source = "mcu")
        tracker.onTick(t(0), snap("A"), 3, 43, source = "mcu")
        tracker.onVolume(t(1_000), 4, 43, source = "mcu")
        tracker.onVolume(t(2_000), 25, 25, source = "audio")
        tracker.onVolume(t(3_000), 26, 25, source = "audio")
        tracker.onVolume(t(4_000), 5, 43, source = "mcu")
        tracker.onVolume(t(5_000), 6, 43, source = "mcu")

        val ended = tracker.onTick(t(6_000), snap("B"), 6, 43, source = "mcu")
            .events.single { it.type == "track_ended" }
        val stats = ended.fields["volumeStats"] as Map<*, *>
        val mcu = stats["mcu"] as Map<*, *>
        val audio = stats["audio"] as Map<*, *>

        assertEquals(3, mcu["start"])
        assertEquals(6, mcu["high"])
        assertEquals(2, mcu["changes"])
        assertEquals(25, audio["start"])
        assertEquals(26, audio["high"])
        assertEquals(1, audio["changes"])
    }

    @Test
    fun `progress содержит источник и максимум громкости`() {
        val tracker = ListeningTracker()
        tracker.onVolume(t(0), 4, 43, source = "mcu")
        tracker.onTick(t(0), snap("A"), 4, 43, source = "mcu")
        val progress = tracker.checkpoint(t(60_000), 4, 43)

        assertEquals("mcu", progress?.fields?.get("volumeSource"))
        assertEquals(43, progress?.fields?.get("volumeMax"))
    }

    @Test
    fun `accessibility без длительности закрывается как unknown`() {
        val tracker = ListeningTracker()
        val started = snap("A").copy(
            packageName = "com.syu.music",
            durationMs = null,
            positionMs = null,
            source = StockPlayerSources.ACCESSIBILITY,
        )
        val next = started.copy(title = "B")
        tracker.onTick(t(0), started, 4, 43, source = "mcu")
        val ended = tracker.onTick(t(10_000), next, 4, 43, source = "mcu")
            .events.single { it.type == "track_ended" }

        assertEquals("unknown", ended.fields["endReason"])
    }

    @Test
    fun `неизвестный максимум не превращается в ноль`() {
        val tracker = ListeningTracker()
        tracker.onVolume(t(0), 4, 0, source = "mcu")

        val events = tracker.onVolume(t(500), 5, 0, source = "mcu")

        assertEquals(1, events.size)
        assertNull(events.first().fields["max"])
        assertNull(events.first().fields["level"])
    }

    @Test
    fun `события трека содержат источник и источник громкости`() {
        val tracker = ListeningTracker()
        tracker.onVolume(t(0), 4, 0, source = "mcu")
        val events = tracker.onTick(t(0), snap(), 4, 0, source = "mcu").events

        val started = events.single { it.type == "track_started" }
        assertEquals(StockPlayerSources.FYT_BROADCAST, started.fields["source"])
        assertEquals("mcu", started.fields["volumeSource"])
        assertTrue(started.fields["volume"] == 4)
    }
}
