package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningTrackerTest {

    private val baseWall = 1_700_000_000_000L

    private fun t(monoMs: Long, wallMs: Long = baseWall + monoMs) = TickTime(
        wallMs = wallMs,
        monoMs = monoMs,
    )

    private fun snap(
        title: String = "Track A",
        positionMs: Long = 0L,
        durationMs: Long? = 180_000L,
        phase: MediaPhase = MediaPhase.PLAYING,
        packageName: String = "com.example.player",
        artist: String? = "Artist",
        source: String = StockPlayerSources.MEDIA_SESSION,
    ) = MediaSnapshot(
        packageName = packageName,
        sessionTag = "session",
        title = title,
        artist = artist,
        album = "Album",
        durationMs = durationMs,
        positionMs = positionMs,
        phase = phase,
        source = source,
    )

    @Test
    fun `дослушанный трек закрывается как completed`() {
        val tracker = ListeningTracker()
        val events = mutableListOf<LogEvent>()

        events += tracker.onTick(t(0), snap("A", 0), 10, 30).events
        events += tracker.onTick(t(10_000), snap("A", 10_000), 10, 30).events
        events += tracker.onTick(t(100_000), snap("A", 100_000), 10, 30).events
        events += tracker.onTick(t(179_000), snap("A", 179_000), 10, 30).events
        events += tracker.onTick(t(180_000), snap("B", 0), 10, 30).events

        val ended = events.single { it.type == "track_ended" }
        assertEquals("completed", ended.fields["endReason"])
        assertEquals(180_000L, ended.fields["playedMs"])
        assertEquals(180_000L, ended.fields["rawPlayedMs"])
        assertEquals("B", events.last { it.type == "track_started" }.fields["title"])
    }

    @Test
    fun `переключение на другую песню того же плеера — skipped`() {
        val tracker = ListeningTracker()
        val events = mutableListOf<LogEvent>()

        events += tracker.onTick(t(0), snap("A", 0), 10, 30).events
        events += tracker.onTick(t(10_000), snap("A", 10_000), 10, 30).events
        events += tracker.onTick(t(11_000), snap("B", 0), 10, 30).events

        val ended = events.single { it.type == "track_ended" }
        assertEquals("skipped", ended.fields["endReason"])
        assertEquals(11_000L, ended.fields["playedMs"])
    }

    @Test
    fun `пауза не съедает прослушанное время`() {
        val tracker = ListeningTracker()
        val events = mutableListOf<LogEvent>()

        events += tracker.onTick(t(0), snap("A", 0), 10, 30).events
        events += tracker.onTick(t(10_000), snap("A", 10_000), 10, 30).events
        events += tracker.onTick(
            t(30_000),
            snap("A", 10_000, phase = MediaPhase.PAUSED),
            10,
            30,
        ).events
        events += tracker.onTick(
            t(40_000),
            snap("A", 10_000, phase = MediaPhase.PAUSED),
            10,
            30,
        ).events
        events += tracker.onTick(t(41_000), snap("A", 11_000), 10, 30).events

        val live = tracker.onTick(t(45_000), snap("A", 15_000), 10, 30).live

        assertTrue(events.none { it.type == "track_ended" })
        assertEquals(34_000L, live?.playedMs)
    }

    @Test
    fun `сброс позиции в ноль на паузе не считается перемоткой`() {
        val tracker = ListeningTracker()
        val events = mutableListOf<LogEvent>()

        events += tracker.onTick(t(0), snap("A", 0), 10, 30).events
        events += tracker.onTick(t(10_000), snap("A", 10_000), 10, 30).events
        // Штатный плеер на паузе отдаёт 0 вместо реальной позиции.
        events += tracker.onTick(
            t(11_000),
            snap("A", 1, phase = MediaPhase.PAUSED),
            10,
            30,
        ).events
        events += tracker.onTick(
            t(13_000),
            snap("A", 0, phase = MediaPhase.PAUSED),
            10,
            30,
        ).events
        // Переключение на следующий трек.
        events += tracker.onTick(t(15_000), snap("B", 0), 10, 30).events

        assertEquals(11_000L, events.single { it.type == "playback_paused" }.fields["positionMs"])
        assertTrue(events.none { it.type == "seek" })
        assertEquals(11_000L, events.single { it.type == "track_ended" }.fields["endPositionMs"])
    }

    @Test
    fun `скачок позиции больше пяти секунд — перемотка`() {
        val tracker = ListeningTracker()
        val events = mutableListOf<LogEvent>()

        events += tracker.onTick(t(0), snap("A", 0), 10, 30).events
        events += tracker.onTick(t(5_000), snap("A", 5_000), 10, 30).events
        events += tracker.onTick(t(6_000), snap("A", 120_000), 10, 30).events

        val seek = events.single { it.type == "seek" }
        assertEquals(114_000L, seek.fields["deltaMs"])
    }

    @Test
    fun `громкость логируется только при изменении`() {
        val tracker = ListeningTracker()

        assertTrue(tracker.onVolume(t(0), 10, 30).isEmpty())
        assertTrue(tracker.onVolume(t(500), 10, 30).isEmpty())

        val events = tracker.onVolume(t(1_000), 13, 30)
        assertEquals(1, events.size)
        assertEquals(10, events.first().fields["previous"])
        assertEquals(13, events.first().fields["value"])
        assertEquals(3, events.first().fields["delta"])
    }

    @Test
    fun `mute не создаёт ложную громкость ноль`() {
        val tracker = ListeningTracker()
        assertTrue(tracker.onVolume(t(0), 30, 43, muted = true).isEmpty())

        val events = tracker.onVolume(t(500), 30, 43, muted = false)

        assertEquals(1, events.size)
        assertEquals("mute_changed", events.single().type)
        assertEquals(30, events.single().fields["volume"])
        assertTrue(events.none { it.type == "volume_changed" })
    }

    @Test
    fun `старт трека пишется один раз`() {
        val tracker = ListeningTracker()

        val first = tracker.onTick(t(0), snap("A", 0), 10, 30)
        val second = tracker.onTick(t(1_000), snap("A", 1_000), 10, 30)

        assertEquals(1, first.events.count { it.type == "track_started" })
        assertTrue(second.events.none { it.type == "track_started" })
    }

    @Test
    fun `пропавшая сессия закрывает трек как session_lost`() {
        val tracker = ListeningTracker()
        tracker.onTick(t(0), snap("A", 0), 10, 30)

        val ended = tracker.onTick(t(10_000), null, 10, 30)
            .events
            .single { it.type == "track_ended" }

        assertEquals("session_lost", ended.fields["endReason"])
    }

    @Test
    fun `session_lost не раздувает playedMs сверх длительности`() {
        val tracker = ListeningTracker()
        tracker.onTick(t(0), snap("A", 0, durationMs = 10_000L), 10, 30)
        tracker.onTick(t(9_000), snap("A", 9_000, durationMs = 10_000L), 10, 30)

        val ended = tracker.onTick(t(15_000), null, 10, 30)
            .events
            .single { it.type == "track_ended" }

        assertEquals("session_lost", ended.fields["endReason"])
        assertEquals(10_000L, ended.fields["playedMs"])
        assertEquals(15_000L, ended.fields["rawPlayedMs"])
    }

    @Test
    fun `unknown без длительности сохраняет raw и не обрезает`() {
        val tracker = ListeningTracker()
        tracker.onTick(
            t(0),
            snap("A", 0, durationMs = null, source = StockPlayerSources.ACCESSIBILITY),
            10,
            30,
        )

        val ended = tracker.onTick(t(6_000), snap("B", 0), 10, 30)
            .events
            .single { it.type == "track_ended" }

        assertEquals("unknown", ended.fields["endReason"])
        assertEquals(6_000L, ended.fields["playedMs"])
        assertEquals(6_000L, ended.fields["rawPlayedMs"])
    }

    @Test
    fun `скачок настенных часов не меняет playedMs`() {
        val tracker = ListeningTracker()
        tracker.onTick(TickTime(wallMs = baseWall, monoMs = 0), snap("A", 0), 10, 30)
        tracker.onTick(
            TickTime(wallMs = baseWall + 3_600_000, monoMs = 10_000),
            snap("A", 10_000),
            10,
            30,
        )

        val live = tracker.onTick(
            TickTime(wallMs = baseWall + 3_601_000, monoMs = 11_000),
            snap("A", 11_000),
            10,
            30,
        ).live

        assertEquals(11_000L, live?.playedMs)
    }

    @Test
    fun `скачок настенных часов назад не ломает grace`() {
        val tracker = ListeningTracker()
        tracker.onTick(TickTime(wallMs = baseWall, monoMs = 0), snap("A", 0), 10, 30)

        val ended = tracker.onTick(
            TickTime(wallMs = baseWall - 3_600_000, monoMs = 10_000),
            null,
            10,
            30,
        ).events.single { it.type == "track_ended" }

        assertEquals("session_lost", ended.fields["endReason"])
    }

    @Test
    fun `repeat-one закрывает трек и начинает новый`() {
        val tracker = ListeningTracker()
        tracker.onTick(t(0), snap("A", 0), 10, 30)
        tracker.onTick(t(100_000), snap("A", 100_000), 10, 30)
        tracker.onTick(t(179_000), snap("A", 179_000), 10, 30)

        val events = tracker.onTick(t(180_000), snap("A", 0), 10, 30).events

        assertEquals(1, events.count { it.type == "track_ended" })
        assertEquals("completed", events.single { it.type == "track_ended" }.fields["endReason"])
        assertEquals(1, events.count { it.type == "track_started" })
    }

    @Test
    fun `поздняя длительность и исполнитель не режут трек`() {
        val tracker = ListeningTracker()
        val started = tracker.onTick(
            t(0),
            snap("A", 0, durationMs = null, artist = null),
            10,
            30,
        ).events.single { it.type == "track_started" }

        val next = tracker.onTick(
            t(1_000),
            snap("A", 1_000, durationMs = 180_000, artist = "Artist"),
            10,
            30,
        )

        assertTrue(next.events.none { it.type == "track_ended" || it.type == "track_started" })
        val ended = tracker.onTick(t(2_000), snap("B", 0), 10, 30)
            .events.single { it.type == "track_ended" }

        assertEquals(started.fields["trackKey"], ended.fields["trackKey"])
    }

    @Test
    fun `смена источника у того же трека не режет трек`() {
        val tracker = ListeningTracker()
        tracker.onTick(
            t(0),
            snap("A", 0, source = StockPlayerSources.NOTIFICATION),
            10,
            30,
        )
        val result = tracker.onTick(
            t(1_000),
            snap("A", 1_000, source = StockPlayerSources.FYT_BROADCAST),
            10,
            30,
        )

        assertTrue(result.events.none { it.type == "track_ended" || it.type == "track_started" })
        assertEquals(StockPlayerSources.FYT_BROADCAST, result.live?.source)
    }

    @Test
    fun `смена fyt_service на accessibility не режет трек`() {
        val tracker = ListeningTracker()
        tracker.onTick(
            t(0),
            snap("A", 0, source = StockPlayerSources.FYT_SERVICE),
            10,
            30,
        )
        val result = tracker.onTick(
            t(1_000),
            snap("A", 1_000, source = StockPlayerSources.ACCESSIBILITY),
            10,
            30,
        )

        assertTrue(result.events.none { it.type == "track_ended" || it.type == "track_started" })
        assertEquals(StockPlayerSources.ACCESSIBILITY, result.live?.source)
    }

    @Test
    fun `старт службы на паузе не создаёт фантомный трек`() {
        val tracker = ListeningTracker()

        val paused = tracker.onTick(
            t(0),
            snap("A", 10_000, phase = MediaPhase.PAUSED),
            10,
            30,
        )
        assertTrue(paused.events.none { it.type == "track_started" })

        val resumed = tracker.onTick(t(1_000), snap("A", 11_000), 10, 30)
        assertEquals(1, resumed.events.count { it.type == "track_started" })
    }

    @Test
    fun `короткий трек получает not_played`() {
        val tracker = ListeningTracker()
        tracker.onTick(t(0), snap("A", 0), 10, 30)
        val ended = tracker.onTick(t(2_000), snap("B", 0), 10, 30)
            .events.single { it.type == "track_ended" }

        assertEquals("not_played", ended.fields["endReason"])
    }

    @Test
    fun `повторный finish не даёт второй track_ended`() {
        val tracker = ListeningTracker()
        tracker.onTick(t(0), snap("A", 0), 10, 30)

        val first = tracker.finish(t(10_000))
        val second = tracker.finish(t(20_000))

        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
        assertEquals("track_ended", first.single().type)
    }

    @Test
    fun `checkpoint пишется раз в минуту и один раз на паузе`() {
        val tracker = ListeningTracker()
        tracker.onTick(t(0), snap("A", 0), 10, 30)

        assertNull(tracker.checkpoint(t(59_000), 10, 30))
        tracker.onTick(t(60_000), snap("A", 60_000), 10, 30)
        val periodic = tracker.checkpoint(t(60_000), 10, 30)
        assertNotNull(periodic)
        assertEquals("track_progress", periodic?.type)
        assertEquals(60_000L, periodic?.fields?.get("playedMs"))
        assertNull(tracker.checkpoint(t(61_000), 10, 30))

        tracker.onTick(
            t(62_000),
            snap("A", 60_000, phase = MediaPhase.PAUSED),
            10,
            30,
        )
        assertNotNull(tracker.checkpoint(t(62_000), 10, 30))
        assertNull(tracker.checkpoint(t(63_000), 10, 30))
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

    @Test
    fun `потеря accessibility окна закрывает трек как session_lost`() {
        val tracker = ListeningTracker()
        val state = snap(
            "A",
            durationMs = null,
            source = StockPlayerSources.ACCESSIBILITY,
        )

        tracker.onTick(t(0), state, 4, 43)
        tracker.onTick(t(4_000), null, 4, 43)
        val events = tracker.onTick(t(6_000), null, 4, 43).events

        assertEquals("session_lost", events.single { it.type == "track_ended" }.fields["endReason"])
    }

    @Test
    fun `tracker сохраняет duration и mediaId при неполном snapshot того же трека`() {
        val tracker = ListeningTracker()
        val first = snap("A", durationMs = 200_000L).copy(mediaId = "/music/a.flac")
        val incomplete = first.copy(
            durationMs = null,
            mediaId = "/music/b.flac",
            phase = MediaPhase.PAUSED,
        )
        val next = snap("B", durationMs = 180_000L).copy(mediaId = "/music/b.flac")

        tracker.onTick(t(0), first, 10, 30)
        tracker.onTick(t(1_000), incomplete, 10, 30)
        val events = tracker.onTick(t(2_000), next, 10, 30).events

        val ended = events.single { it.type == "track_ended" }
        assertEquals("A", ended.fields["title"])
        assertEquals("/music/a.flac", ended.fields["mediaId"])
        assertEquals(200_000L, ended.fields["durationMs"])
    }

    @Test
    fun `сон на паузе закрывает трек на последнем тике`() {
        val tracker = ListeningTracker()
        tracker.onTick(t(0), snap("A"), 10, 30)
        tracker.onTick(t(1_000), snap("A", phase = MediaPhase.PAUSED), 10, 30)

        val events = tracker.onTick(
            t(9 * 60 * 60 * 1_000L),
            snap("A", phase = MediaPhase.PAUSED),
            10,
            30,
        ).events

        val ended = events.single { it.type == "track_ended" }
        assertEquals("session_lost", ended.fields["endReason"])
        assertEquals(baseWall + 1_000, ended.ts)
        assertEquals(1_000L, ended.fields["playedMs"])
        assertTrue(events.none { it.type == "track_started" })
    }

    @Test
    fun `сон во время игры закрывает трек и начинает новый`() {
        val tracker = ListeningTracker()
        tracker.onTick(t(0), snap("A"), 10, 30)
        tracker.onTick(t(10_000), snap("A"), 10, 30)

        val events = tracker.onTick(t(10 * 60 * 1_000L), snap("A"), 10, 30).events

        val ended = events.single { it.type == "track_ended" }
        assertEquals("session_lost", ended.fields["endReason"])
        assertEquals(baseWall + 10_000, ended.ts)
        assertEquals(10_000L, ended.fields["playedMs"])
        assertEquals(
            baseWall + 10 * 60 * 1_000L,
            events.single { it.type == "track_started" }.ts,
        )
    }

    @Test
    fun `обычный тик меньше двух минут не закрывает трек`() {
        val tracker = ListeningTracker()
        tracker.onTick(t(0), snap("A"), 10, 30)
        val events = tracker.onTick(t(100_000), snap("A"), 10, 30).events

        assertTrue(events.none { it.type == "track_ended" })
    }
}
