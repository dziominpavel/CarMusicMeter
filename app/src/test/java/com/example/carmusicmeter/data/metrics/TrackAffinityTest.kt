package com.example.carmusicmeter.data.metrics

import com.example.carmusicmeter.data.PlayRecord
import com.example.carmusicmeter.data.PlayVolume
import com.example.carmusicmeter.data.playRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackAffinityTest {

    private val defaults = TrackAffinity.calibrate(emptyList())

    @Test
    fun `старый факт без агрегатов читается по start low high и помечается приблизительным`() {
        val play = play(
            start = 10,
            low = 6,
            high = 16,
            up = null,
            down = null,
            level = null,
            endReason = "completed",
            playedMs = 180_000,
        )

        val result = TrackAffinity.score(listOf(play), defaults, emptyMap())

        requireNotNull(result)
        assertTrue(result.approximate)
        assertEquals(6, result.maxUp)
        assertTrue(result.score in 37..70)
    }

    @Test
    fun `новый факт с агрегатами не приблизительный`() {
        val play = play(up = 5, down = 0, level = 12)

        val result = TrackAffinity.score(listOf(play), defaults, emptyMap())

        requireNotNull(result)
        assertFalse(result.approximate)
    }

    @Test
    fun `калибровка берёт потолок и привычку из истории`() {
        val plays = (0 until 40).map { index ->
            play(
                startedAt = 1_000L + index * 60_000L,
                start = 20,
                low = 18,
                high = 25,
                up = 3,
                down = 2,
                level = 22,
            )
        }

        val calibration = TrackAffinity.calibrate(plays)

        assertTrue(calibration.trained)
        assertEquals(22, calibration.habit)
        assertEquals(25, calibration.ceiling)
        assertEquals(3, calibration.typicalUp)
        assertEquals(2, calibration.typicalDown)
    }

    @Test
    fun `мало истории — черновые значения по умолчанию`() {
        val calibration = TrackAffinity.calibrate(listOf(play(), play(startedAt = 2_000L)))

        assertFalse(calibration.trained)
        assertEquals(30, calibration.ceiling)
        assertEquals(15, calibration.habit)
        assertEquals(5, calibration.typicalUp)
        assertEquals(3, calibration.typicalDown)
    }

    @Test
    fun `подъём громкости повышает оценку`() {
        val quiet = play(up = 0, down = 0, level = 12)
        val loud = play(up = 6, down = 0, level = 12)

        val quietScore = TrackAffinity.score(listOf(quiet), defaults, emptyMap())!!.score
        val loudScore = TrackAffinity.score(listOf(loud), defaults, emptyMap())!!.score

        assertTrue(loudScore > quietScore)
    }

    @Test
    fun `один и тот же подъём при громком старте даёт больше`() {
        val quiet = play(start = 5, up = 5, down = 0, level = 10)
        val loud = play(start = 24, up = 5, down = 0, level = 26)

        val quietScore = TrackAffinity.score(listOf(quiet), defaults, emptyMap())!!.score
        val loudScore = TrackAffinity.score(listOf(loud), defaults, emptyMap())!!.score

        assertTrue(loudScore > quietScore)
    }

    @Test
    fun `дослушанный выше скипнутого при той же громкости`() {
        val skipped = play(endReason = "skipped", playedMs = 18_000, up = 0, down = 0, level = 12)
        val completed = play(endReason = "completed", playedMs = 180_000, up = 0, down = 0, level = 12)

        val skippedScore = TrackAffinity.score(listOf(skipped), defaults, emptyMap())!!.score
        val completedScore = TrackAffinity.score(listOf(completed), defaults, emptyMap())!!.score

        assertTrue(completedScore > skippedScore)
    }

    @Test
    fun `понижение у дослушанного не ухудшает оценку`() {
        val noDrop = play(endReason = "completed", playedMs = 180_000, up = 0, down = 0, level = 12)
        val parking = play(
            endReason = "completed",
            playedMs = 180_000,
            up = 0,
            down = 20,
            level = 12,
            dropMs = 170_000,
        )

        val a = TrackAffinity.score(listOf(noDrop), defaults, emptyMap())!!.score
        val b = TrackAffinity.score(listOf(parking), defaults, emptyMap())!!.score

        assertEquals(a, b)
    }

    @Test
    fun `понижение у скипнутого ухудшает оценку`() {
        val plain = play(endReason = "skipped", playedMs = 20_000, up = 0, down = 0, level = 12)
        val dropped = play(endReason = "skipped", playedMs = 20_000, up = 0, down = 10, level = 12)

        val a = TrackAffinity.score(listOf(plain), defaults, emptyMap())!!.score
        val b = TrackAffinity.score(listOf(dropped), defaults, emptyMap())!!.score

        assertTrue(b < a)
    }

    @Test
    fun `одно прослушивание не даёт 0 или 100`() {
        val best = play(endReason = "completed", playedMs = 180_000, start = 20, up = 20, level = 26)
        val worst = play(endReason = "skipped", playedMs = 3_000, start = 20, up = 0, down = 20, level = 8)

        val top = TrackAffinity.score(listOf(best), defaults, emptyMap())!!.score
        val bottom = TrackAffinity.score(listOf(worst), defaults, emptyMap())!!.score

        assertTrue(top in 37..70)
        assertTrue(bottom in 37..70)
    }

    @Test
    fun `десять прослушиваний устойчивы`() {
        val plays = (0 until 10).map { index ->
            play(
                startedAt = 1_000L + index * 60_000L,
                endReason = "completed",
                playedMs = 180_000,
                up = 5,
                level = 20,
            )
        }

        val nine = TrackAffinity.score(plays.drop(1), defaults, emptyMap())!!.score
        val ten = TrackAffinity.score(plays, defaults, emptyMap())!!.score
        val eleven = TrackAffinity.score(plays + play(startedAt = 100_000_000L, endReason = "completed", playedMs = 180_000, up = 5, level = 20), defaults, emptyMap())!!.score

        assertTrue(ten in 70..100)
        assertTrue(kotlin.math.abs(ten - nine) <= 3)
        assertTrue(kotlin.math.abs(eleven - ten) <= 3)
    }

    @Test
    fun `нет прослушиваний — прочерк`() {
        assertNull(TrackAffinity.score(emptyList(), defaults, emptyMap()))

        val empty = play(endReason = "not_played", playedMs = 600, up = 0, down = 0, level = 12)
        assertNull(TrackAffinity.score(listOf(empty), defaults, emptyMap()))
    }

    @Test
    fun `разбор называет подъём и место в треке`() {
        val play = play(up = 5, down = 0, level = 20, raiseMs = 14_000)

        val result = TrackAffinity.score(listOf(play), defaults, emptyMap())!!

        assertTrue(result.reasons.toString(), result.reasons.any { it == "подъём +5 на 0:14" })
        assertTrue(result.reasons.any { it.contains("прослушано в среднем") })
    }

    private fun play(
        startedAt: Long = 1_000L,
        endReason: String = "skipped",
        playedMs: Long = 30_000,
        durationMs: Long = 180_000,
        start: Int = 10,
        low: Int = start,
        high: Int = start,
        up: Int? = 0,
        down: Int? = 0,
        level: Int? = start,
        raiseMs: Long? = null,
        dropMs: Long? = null,
    ): PlayRecord = playRecord(
        playId = "p-" + startedAt + "-" + endReason + "-" + playedMs,
        trackKey = "k-" + startedAt + "-" + endReason,
        startedAt = startedAt,
        endedAt = startedAt + playedMs,
        endReason = endReason,
        playedMs = playedMs,
        durationMs = durationMs,
    ).copy(
        volumeStats = mapOf(
            "mcu" to PlayVolume(
                start = start,
                low = low,
                high = high,
                end = high,
                max = 43,
                changes = 1,
                up = up,
                down = down,
                level = level,
                raiseMs = raiseMs,
                dropMs = dropMs,
            ),
        ),
    )
}
