package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashTimelineTest {

    private val tolerance = 0.0005f

    @Test
    fun firstFrameIsEmpty() {
        val frame = SplashTimeline.frame(0f)

        assertEquals(0f, frame.alpha, tolerance)
        assertEquals(0f, frame.arc, tolerance)
        assertEquals(0f, frame.glint, tolerance)
    }

    @Test
    fun lastFrameIsFinal() {
        val frame = SplashTimeline.frame(1f)

        assertEquals(1f, frame.alpha, tolerance)
        assertEquals(1f, frame.scale, tolerance)
        assertEquals(1f, frame.arc, tolerance)
        assertEquals(1f, frame.glint, tolerance)
        assertTrue("свечение садится к концу", frame.glow in 0.4f..0.6f)
    }

    @Test
    fun arcAndGlintGrowMonotonically() {
        var previous = SplashTimeline.frame(0f)
        var progress = 0.01f
        while (progress <= 1f) {
            val frame = SplashTimeline.frame(progress)
            assertTrue("дуга не откатывается на $progress", frame.arc >= previous.arc)
            assertTrue("волна не откатывается на $progress", frame.glint >= previous.glint)
            previous = frame
            progress += 0.01f
        }
    }

    @Test
    fun scalePeaksThenSettles() {
        val peak = SplashTimeline.frame(0.72f)

        assertEquals(1.012f, peak.scale, 0.001f)
        assertTrue("после пика масштаб уменьшается", SplashTimeline.frame(0.9f).scale < peak.scale)
        assertEquals(1f, SplashTimeline.frame(1f).scale, tolerance)
    }

    @Test
    fun alphaFinishesEarly() {
        assertEquals(1f, SplashTimeline.frame(0.22f).alpha, tolerance)
        assertTrue(SplashTimeline.frame(0.1f).alpha in 0.01f..0.99f)
    }

    @Test
    fun everyValueStaysInUnitRange() {
        var progress = 0f
        while (progress <= 1f) {
            val frame = SplashTimeline.frame(progress)
            listOf(frame.alpha, frame.scale, frame.glow, frame.arc, frame.glint).forEach {
                assertTrue("значение вне 0..1 на $progress: $it", it in 0f..1.0121f)
            }
            progress += 0.01f
        }
    }

    @Test
    fun totalDurationFitsTwoSeconds() {
        val total = SplashTimeline.ANIMATION_MS +
            SplashTimeline.HOLD_MS +
            SplashTimeline.FADE_OUT_MS

        assertTrue("заставка не длиннее двух секунд: $total", total <= 2000)
    }

    @Test
    fun watchdogCannotCutTheAnimation() {
        val total = SplashTimeline.ANIMATION_MS +
            SplashTimeline.HOLD_MS +
            SplashTimeline.FADE_OUT_MS

        assertTrue("страховка позже конца таймлайна", SplashTimeline.WATCHDOG_MS > total)
    }

    @Test
    fun progressOutsideRangeIsClamped() {
        assertEquals(SplashTimeline.frame(0f), SplashTimeline.frame(-1f))
        assertEquals(SplashTimeline.frame(1f), SplashTimeline.frame(2f))
    }
}
