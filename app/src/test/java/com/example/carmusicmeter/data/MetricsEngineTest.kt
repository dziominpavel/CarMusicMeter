package com.example.carmusicmeter.data

import com.example.carmusicmeter.data.metrics.MetricsEngine
import com.example.carmusicmeter.data.metrics.ReportPeriod
import org.junit.Assert.assertEquals
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
        assertEquals(1, model.candidates.size)
        assertEquals("c", model.candidates.single().trackKey)
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
        )
    }
}
