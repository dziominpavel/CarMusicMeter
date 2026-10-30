package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningTrackerTest {

    private fun snap(
        title: String = "Track A",
        positionMs: Long = 0L,
        durationMs: Long = 180_000L,
        phase: MediaPhase = MediaPhase.PLAYING,
        packageName: String = "com.example.player",
        artist: String = "Artist",
    ) = MediaSnapshot(
        packageName = packageName,
        sessionTag = "session",
        title = title,
        artist = artist,
        album = "Album",
        durationMs = durationMs,
        positionMs = positionMs,
        phase = phase,
    )

    @Test
    fun `дослушанный трек закрывается как completed`() {
        val tracker = ListeningTracker()
        val events = mutableListOf<LogEvent>()

        events += tracker.onTick(0, snap("A", 0), 10, 30).events
        events += tracker.onTick(10_000, snap("A", 10_000), 10, 30).events
        events += tracker.onTick(179_000, snap("A", 179_000), 10, 30).events
        events += tracker.onTick(180_000, snap("B", 0), 10, 30).events

        val ended = events.single { it.type == "track_ended" }
        assertEquals("completed", ended.fields["endReason"])
        assertEquals(180_000L, ended.fields["playedMs"])
        assertEquals("B", events.last { it.type == "track_started" }.fields["title"])
    }

    @Test
    fun `переключение на другую песню того же плеера — skipped`() {
        val tracker = ListeningTracker()
        val events = mutableListOf<LogEvent>()

        events += tracker.onTick(0, snap("A", 0), 10, 30).events
        events += tracker.onTick(10_000, snap("A", 10_000), 10, 30).events
        events += tracker.onTick(11_000, snap("B", 0), 10, 30).events

        val ended = events.single { it.type == "track_ended" }
        assertEquals("skipped", ended.fields["endReason"])
        assertEquals(11_000L, ended.fields["playedMs"])
    }

    @Test
    fun `пауза не съедает прослушанное время`() {
        val tracker = ListeningTracker()
        val events = mutableListOf<LogEvent>()

        events += tracker.onTick(0, snap("A", 0), 10, 30).events
        events += tracker.onTick(10_000, snap("A", 10_000), 10, 30).events
        events += tracker.onTick(30_000, snap("A", 10_000, phase = MediaPhase.PAUSED), 10, 30).events
        events += tracker.onTick(40_000, snap("A", 10_000, phase = MediaPhase.PAUSED), 10, 30).events
        events += tracker.onTick(41_000, snap("A", 11_000), 10, 30).events

        val live = tracker.onTick(45_000, snap("A", 15_000), 10, 30).live

        assertTrue(events.none { it.type == "track_ended" })
        assertEquals(34_000L, live?.playedMs)
    }

    @Test
    fun `скачок позиции больше пяти секунд — перемотка`() {
        val tracker = ListeningTracker()
        val events = mutableListOf<LogEvent>()

        events += tracker.onTick(0, snap("A", 0), 10, 30).events
        events += tracker.onTick(5_000, snap("A", 5_000), 10, 30).events
        events += tracker.onTick(6_000, snap("A", 120_000), 10, 30).events

        val seek = events.single { it.type == "seek" }
        assertEquals(114_000L, seek.fields["deltaMs"])
    }

    @Test
    fun `громкость логируется только при изменении`() {
        val tracker = ListeningTracker()

        assertTrue(tracker.onVolume(0, 10, 30).isEmpty())
        assertTrue(tracker.onVolume(500, 10, 30).isEmpty())

        val events = tracker.onVolume(1_000, 13, 30)
        assertEquals(1, events.size)
        assertEquals(10, events.first().fields["previous"])
        assertEquals(13, events.first().fields["value"])
        assertEquals(3, events.first().fields["delta"])
    }

    @Test
    fun `старт трека пишется один раз`() {
        val tracker = ListeningTracker()

        val first = tracker.onTick(0, snap("A", 0), 10, 30)
        val second = tracker.onTick(1_000, snap("A", 1_000), 10, 30)

        assertEquals(1, first.events.count { it.type == "track_started" })
        assertTrue(second.events.none { it.type == "track_started" })
    }

    @Test
    fun `пропавшая сессия закрывает трек как session_lost`() {
        val tracker = ListeningTracker()
        tracker.onTick(0, snap("A", 0), 10, 30)

        val ended = tracker.onTick(10_000, null, 10, 30)
            .events
            .single { it.type == "track_ended" }

        assertEquals("session_lost", ended.fields["endReason"])
    }

    @Test
    fun `picker предпочитает текущий играющий пакет`() {
        val other = snap("B", packageName = "com.other")
        val current = snap("A", packageName = "com.example.player")

        assertEquals(
            "A",
            SnapshotPicker.pick(listOf(other, current), "com.example.player")?.title,
        )
    }

    @Test
    fun `picker переключается на играющий пакет, если текущий на паузе`() {
        val other = snap("B", packageName = "com.other")
        val current = snap("A", packageName = "com.example.player", phase = MediaPhase.PAUSED)

        assertEquals(
            "B",
            SnapshotPicker.pick(listOf(other, current), "com.example.player")?.title,
        )
    }

    @Test
    fun `сессия без названия не выбирается`() {
        assertNull(
            SnapshotPicker.pick(listOf(snap(title = "", positionMs = 1_000)), null),
        )
    }
}
