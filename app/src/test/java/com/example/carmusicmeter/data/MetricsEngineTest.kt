package com.example.carmusicmeter.data

import com.example.carmusicmeter.data.metrics.MetricsEngine
import com.example.carmusicmeter.data.metrics.ReportPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsEngineTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `сводка, топ, антитоп и кандидаты считаются по фактам`() {
        val plays = listOf(
            play("a", "skipped", 30_000, 10),
            play("a", "skipped", 30_000, 20),
            play("a", "skipped", 30_000, 30),
            play("a", "completed", 180_000, 40),
            play("b", "skipped", 30_000, 50),
            play("b", "skipped", 30_000, 60),
            play("c", "skipped", 20_000, 70, duration = 120_000),
            play("c", "skipped", 20_000, 80, duration = 120_000),
            play("c", "skipped", 20_000, 90, duration = 120_000),
            play("a", "skipped", 30_000, 100, verified = false),
            play("a", "open", 10_000, 5, open = true),
        )

        val model = MetricsEngine.compute(
            plays = plays,
            period = ReportPeriod.DAYS_7,
            captureGaps = 2,
            nowMs = now,
        )

        assertEquals(9, model.summary.plays)
        assertEquals(1, model.summary.completed)
        assertEquals(8, model.summary.skipped)
        assertEquals(390_000L, model.summary.playedMs)
        assertEquals(3, model.summary.tracks)
        assertEquals(1, model.summary.trips)
        assertEquals("a", model.top.first().trackKey)
        assertEquals("c", model.antiTop.first().trackKey)
        assertEquals(0, model.candidates.size)
        assertEquals(1, model.quality.open)
        assertEquals(1, model.quality.unverified)
        assertEquals(2, model.quality.captureGaps)
        assertEquals(1.0, model.quality.durationCoverage ?: 0.0, 0.0001)
        assertTrue(model.track("a")?.enoughData == true)
        assertEquals(1, model.unverifiedCount)
        assertEquals(0.75, model.track("a")?.skipRate ?: 0.0, 0.0001)
    }

    @Test
    fun `пустой период не притворяется нулём`() {
        val model = MetricsEngine.compute(emptyList(), ReportPeriod.ALL, 0, now)

        assertEquals(0, model.summary.plays)
        assertEquals(0, model.summary.tracks)
        assertNull(model.quality.durationCoverage)
        assertNull(model.openPlay)
    }

    private fun play(
        track: String,
        reason: String,
        played: Long,
        minutesAgo: Long,
        duration: Long = 180_000L,
        verified: Boolean = true,
        open: Boolean = false,
        up: Int = 2,
        down: Int = 0,
        level: Int = 11,
    ): PlayRecord {
        val started = now - minutesAgo * 60_000L
        return playRecord(
            playId = track + "-" + minutesAgo,
            trackKey = track,
            title = "Track " + track,
            artist = "Artist " + track,
            startedAt = started,
            endedAt = started + played,
            endReason = reason,
            playedMs = played,
            durationMs = duration,
            endPositionMs = played,
            clockVerified = verified,
            open = open,
        ).copy(
            volumeStats = mapOf(
                "mcu" to PlayVolume(10, 10, 12, 12, 43, 1, up = up, down = down, level = level),
            ),
        )
    }

    @Test
    fun `рейтинг ставит низкую оценку вниз, треки без оценки — последними`() {
        val plays = listOf(
            play("good", "completed", 180_000, 10, up = 5, level = 20),
            play("bad", "skipped", 6_000, 20, up = 0, down = 10, level = 8),
            play("empty", "not_played", 600, 30, up = 0, down = 0),
        )

        val model = MetricsEngine.compute(plays, ReportPeriod.DAYS_7, 0, now)

        assertEquals(listOf("good", "bad", "empty"), model.rankedTracks.map { it.trackKey })
        assertNotNull(model.track("good")?.affinity)
        assertNotNull(model.track("bad")?.affinity)
        assertNull(model.track("empty")?.affinity)
    }

    @Test
    fun `кандидат требует низкой оценки, двух дней и отсутствия подъёмов`() {
        fun low(daysAgo: Long, up: Int): PlayRecord = play(
            track = "candidate",
            reason = "skipped",
            played = 6_000,
            minutesAgo = daysAgo * 1_440,
            duration = 120_000,
            up = up,
            down = 10,
            level = 8,
        )

        val model = MetricsEngine.compute(
            plays = listOf(low(1, 0), low(2, 0), low(3, 0)),
            period = ReportPeriod.DAYS_7,
            nowMs = now,
        )

        assertEquals(1, model.candidates.size)
        assertEquals("candidate", model.candidates.single().trackKey)
        assertTrue((model.candidates.single().affinity ?: 100) <= 30)

        val withRaise = MetricsEngine.compute(
            plays = listOf(low(1, 0), low(2, 0), low(3, 4)),
            period = ReportPeriod.DAYS_7,
            nowMs = now,
        )
        assertEquals(0, withRaise.candidates.size)
    }
}
