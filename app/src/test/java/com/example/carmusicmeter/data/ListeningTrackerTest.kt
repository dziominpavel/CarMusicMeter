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
    fun `переходный intent с длительностью следующего трека не подменяет поля`() {
        val tracker = ListeningTracker()
        // Случай из журнала 2026-11-05: плеер на переходе отдаёт старый title,
        // но новый путь и длительность следующего трека.
        val started = snap("What Do You Mean?", durationMs = 208_543L)
            .copy(mediaId = "/music/justin.flac")
        val transition = snap("What Do You Mean?", durationMs = 242_187L).copy(
            mediaId = "/music/prishla-pora.flac",
            phase = MediaPhase.PAUSED,
        )
        val next = snap("Пришла пора", durationMs = 242_187L)
            .copy(mediaId = "/music/prishla-pora.flac")

        tracker.onTick(t(0), started, 10, 30)
        tracker.onTick(t(34_000), transition, 10, 30)
        val events = tracker.onTick(t(34_100), next, 10, 30).events

        val ended = events.single { it.type == "track_ended" }
        assertEquals("What Do You Mean?", ended.fields["title"])
        assertEquals("/music/justin.flac", ended.fields["mediaId"])
        assertEquals(208_543L, ended.fields["durationMs"])
        assertEquals("Пришла пора", events.last { it.type == "track_started" }.fields["title"])
    }

    @Test
    fun `нулевая длительность считается неизвестной и дописывается позже`() {
        val tracker = ListeningTracker()

        tracker.onTick(t(0), snap("A", durationMs = 0L), 10, 30)
        tracker.onTick(t(1_000), snap("A", durationMs = 180_000L), 10, 30)
        val events = tracker.onTick(t(2_000), snap("B", durationMs = 180_000L), 10, 30).events

        assertEquals(180_000L, events.single { it.type == "track_ended" }.fields["durationMs"])
    }

    @Test
    fun `восстановленное прослушивание продолжается без второго старта`() {
        val tracker = ListeningTracker()
        val live = snap("Track A", 31_000)
        val open = openPlay(trackKey = live.trackKey)

        assertTrue(tracker.adoptRestored(open, t(0), live))
        val sameTick = tracker.onTick(t(1_000), snap("Track A", 32_000), 10, 30)
        assertTrue(sameTick.events.none { it.type == "track_started" })

        val events = tracker.onTick(t(31_000), snap("B"), 10, 30).events
        val ended = events.single { it.type == "track_ended" }
        assertEquals("Track A", ended.fields["title"])
        assertEquals(baseWall - 30_000L, ended.fields["startedAt"])
        assertTrue((ended.fields["playedMs"] as Long) >= 60_000L)
        assertEquals(null, ended.fields["restored"])
    }

    @Test
    fun `закрытие восстановленного прослушивания помечено restored`() {
        val tracker = ListeningTracker()

        val event = tracker.closeRestored(openPlay(trackKey = snap("Track A").trackKey), t(0))

        assertEquals("session_lost", event.fields["endReason"])
        assertEquals(true, event.fields["restored"])
        assertEquals(30_000L, event.fields["playedMs"])
        assertEquals(30_000L, event.fields["rawPlayedMs"])
        assertEquals("Track A", event.fields["title"])
        assertEquals(180_000L, event.fields["durationMs"])
    }

    @Test
    fun `занятый трекер не принимает восстановление`() {
        val tracker = ListeningTracker()
        tracker.onTick(t(0), snap("X"), 10, 30)

        val open = openPlay(trackKey = snap("Track A").trackKey)

        assertTrue(!tracker.adoptRestored(open, t(1_000), snap("Track A", 0)))
    }

    private fun openPlay(
        trackKey: String,
        playedMs: Long = 30_000L,
    ) = OpenPlay(
        bootId = "previous",
        trackKey = trackKey,
        packageName = "com.example.player",
        source = StockPlayerSources.MEDIA_SESSION,
        title = "Track A",
        artist = "Artist",
        album = "Album",
        mediaId = "/music/a.flac",
        durationMs = 180_000L,
        positionMs = 30_000L,
        playedMs = playedMs,
        startedAtWallMs = baseWall - 30_000L,
        lastTsWallMs = baseWall - 1_000L,
        volumeStart = 10,
        volumeLow = 10,
        volumeHigh = 12,
        volumeEnd = 12,
        volumeMax = 30,
        volumeChanges = 2,
        volumeSource = "audio",
    )

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

    @Test
    fun `открытое прослушивание сохраняется пока трек активен и пропадает после закрытия`() {
        val tracker = ListeningTracker()
        assertNull(tracker.openPlay("boot1", t(0)))

        tracker.onTick(t(0), snap("A", 15_000), 10, 30)
        tracker.onTick(t(30_000), snap("A", 45_000), 10, 30)
        val open = tracker.openPlay("boot1", t(30_000))
        assertNotNull(open)
        assertEquals("boot1", open?.bootId)
        assertEquals(30_000L, open?.playedMs)
        assertEquals("A", open?.title)

        tracker.finish(t(40_000))
        assertNull(tracker.openPlay("boot1", t(50_000)))
    }

    @Test
    fun `обрыв на паузе восстанавливает playedMs из контрольной точки`() {
        val tracker = ListeningTracker()
        tracker.onTick(t(0), snap("A", 0), 10, 30)
        tracker.onTick(t(20_000), snap("A", 20_000, phase = MediaPhase.PAUSED), 10, 30)

        val open = tracker.openPlay("boot1", t(10 * 60_000))!!
        val restored = ListeningTracker().closeRestored(open, t(0))

        assertEquals("session_lost", restored.fields["endReason"])
        assertEquals(20_000L, restored.fields["playedMs"])
        assertEquals(20_000L, restored.fields["endPositionMs"])
    }

    @Test
    fun `восстановленное прослушивание закрывается одним session_lost`() {
        val tracker = ListeningTracker()
        tracker.onTick(t(0), snap("A", 15_000), 10, 30)
        tracker.onTick(t(30_000), snap("A", 45_000), 10, 30)
        val open = tracker.openPlay("boot1", t(30_000))!!

        val restored = ListeningTracker().closeRestored(open, t(0, wallMs = baseWall + 8 * 60 * 60 * 1_000L))

        assertEquals("track_ended", restored.type)
        assertEquals("session_lost", restored.fields["endReason"])
        assertEquals(30_000L, restored.fields["playedMs"])
        assertEquals(30_000L, restored.fields["rawPlayedMs"])
        assertEquals("A", restored.fields["title"])
        assertEquals(180_000L, restored.fields["durationMs"])
        assertEquals(false, restored.fields["completed"])
    }

    @Test
    fun `восстановленное прослушивание не длиннее трека`() {
        val tracker = ListeningTracker()
        tracker.onTick(t(0), snap("A", 0), 10, 30)
        tracker.onTick(t(200_000), snap("A", 200_000), 10, 30)
        val open = tracker.openPlay("boot1", t(200_000))!!.copy(playedMs = 250_000L)

        val restored = ListeningTracker().closeRestored(open, t(0))

        assertEquals(180_000L, restored.fields["playedMs"])
        assertEquals(250_000L, restored.fields["rawPlayedMs"])
    }

    @Test
    fun `восстановленное прослушивание сохраняет статистику громкости`() {
        val tracker = ListeningTracker()
        tracker.onTick(t(0), snap("A", 0), 4, 43, source = "mcu")
        tracker.onVolume(t(1_000), 9, 43, source = "mcu")
        val open = tracker.openPlay("boot1", t(2_000))!!

        val restored = ListeningTracker().closeRestored(open, t(0))

        @Suppress("UNCHECKED_CAST")
        val stats = restored.fields["volumeStats"] as Map<String, Map<String, Any?>>
        val mcu = stats.getValue("mcu")
        assertEquals(4, mcu["start"])
        assertEquals(9, mcu["end"])
        assertEquals(1, mcu["changes"])
        assertEquals(43, mcu["max"])
    }

    @Test
    fun `агрегаты громкости копят шаги, уровень и места изменений`() {
        val tracker = ListeningTracker()
        tracker.onTick(t(0), snap("A", 0), 25, 43, source = "mcu")
        tracker.onVolume(t(10_000), 17, 43, source = "mcu")
        tracker.onVolume(t(20_000), 22, 43, source = "mcu")
        val ended = tracker.onTick(t(30_000), snap("B", 0), 22, 43, source = "mcu").events
            .single { it.type == "track_ended" }

        @Suppress("UNCHECKED_CAST")
        val stats = ended.fields["volumeStats"] as Map<String, Map<String, Any?>>
        val mcu = stats.getValue("mcu")
        assertEquals(5, mcu["up"])
        assertEquals(8, mcu["down"])
        assertEquals(22, mcu["level"])
        assertEquals(10_000L, mcu["dropMs"])
        assertEquals(20_000L, mcu["raiseMs"])
    }

    @Test
    fun `playId переживает восстановление прослушивания`() {
        val tracker = ListeningTracker()
        tracker.onTick(t(0), snap("A", 0), 10, 30)
        val open = tracker.openPlay("boot1", t(10_000))!!

        val restored = ListeningTracker().closeRestored(open, t(0))

        assertTrue(open.playId.isNotBlank())
        assertEquals(open.playId, restored.fields["playId"])
    }

    @Test
    fun `перемотки считаются и попадают в факт`() {
        val tracker = ListeningTracker()
        val started = tracker.onTick(t(0), snap("A", 0), 10, 30).events
            .single { it.type == "track_started" }
        tracker.onTick(t(10_000), snap("A", 10_000), 10, 30)
        tracker.onTick(t(11_000), snap("A", 1_000), 10, 30)
        tracker.onTick(t(12_000), snap("A", 20_000), 10, 30)
        val ended = tracker.onTick(t(13_000), snap("B", 0), 10, 30).events
            .single { it.type == "track_ended" }

        assertEquals(started.fields["playId"], ended.fields["playId"])
        assertEquals(1, ended.fields["seekBack"])
        assertEquals(1, ended.fields["seekForward"])
    }
}
