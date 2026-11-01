package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureHealthTest {

    @Test
    fun `ошибки накапливаются а успех сбрасывает состояние`() {
        val failed = CaptureHealth()
            .tickAttempt(1_000)
            .failure("append", dropped = 3)
            .failure("append", dropped = 2)

        assertEquals(2, failed.consecutiveFailures)
        assertEquals(5, failed.droppedEvents)
        assertEquals("append", failed.lastError)

        val recovered = failed.success(2_000)
        assertEquals(0, recovered.consecutiveFailures)
        assertEquals(5, recovered.droppedEvents)
        assertEquals(null, recovered.lastError)
        assertEquals(2_000L, recovered.lastSuccessAt)
    }

    @Test
    fun `backoff растёт и ограничен`() {
        assertEquals(0L, TickBackoff.delayMs(0))
        assertEquals(1_000L, TickBackoff.delayMs(1))
        assertEquals(2_000L, TickBackoff.delayMs(2))
        assertEquals(5_000L, TickBackoff.delayMs(3))
        assertEquals(TickBackoff.MAX_DELAY_MS, TickBackoff.delayMs(4))
        assertEquals(TickBackoff.MAX_DELAY_MS, TickBackoff.delayMs(100))
    }

    @Test
    fun `ошибка логируется не на каждом тике`() {
        assertTrue(CaptureErrorThrottle.shouldLog(1_000, null, failures = 1))
        assertFalse(CaptureErrorThrottle.shouldLog(2_000, 1_000, failures = 2))
        assertFalse(CaptureErrorThrottle.shouldLog(60_999, 1_000, failures = 3))
        assertTrue(CaptureErrorThrottle.shouldLog(61_000, 1_000, failures = 4))
    }

    @Test
    fun `скачок часов обнаруживается только при расхождении`() {
        assertEquals(
            ClockJump(wallDeltaMs = 3_601_000, monoDeltaMs = 1_000),
            ClockJumpDetector.detect(1_000, 1_000, 3_602_000, 2_000),
        )
        assertEquals(null, ClockJumpDetector.detect(1_000, 1_000, 2_000, 2_000))
        assertEquals(null, ClockJumpDetector.detect(null, null, 2_000, 2_000))
    }

    @Test
    fun `ошибка записи пачки не теряется и содержит число событий`() {
        val events = listOf(
            LogEvent(1_000, "track_started"),
            LogEvent(1_000, "track_progress"),
        )

        val error = try {
            CaptureWrite.append(events) { throw java.io.IOException("disk full") }
            null
        } catch (error: CaptureWriteException) {
            error
        }

        assertEquals(2, error?.droppedEvents)
        assertEquals("disk full", error?.cause?.message)
    }

    @Test
    fun `успешная запись передаёт пачку sink`() {
        var received = 0
        CaptureWrite.append(listOf(LogEvent(1_000, "track_started"))) { received = it.size }
        assertEquals(1, received)
    }
}
