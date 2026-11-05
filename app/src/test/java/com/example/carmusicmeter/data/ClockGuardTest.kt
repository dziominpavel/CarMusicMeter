package com.example.carmusicmeter.data

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockGuardTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    /** Сборка от 04.11.2026 22:56 — раньше этого времени часы идти не могут. */
    private val buildTime = 1_793_832_960_000L

    /** Реальное время поездки 2026-11-05 18:41. */
    private val realNow = 1_793_893_274_778L

    /** Как часы магнитолы выглядят после полной потери питания. */
    private val pretend2023 = 1_683_706_439_724L

    @Test
    fun `часы до даты сборки недостоверны`() {
        assertFalse(isClockPlausible(pretend2023, buildTime, realNow))
    }

    @Test
    fun `верное время достоверно`() {
        assertTrue(isClockPlausible(realNow, buildTime, realNow - 60_000L))
    }

    @Test
    fun `откат больше часа недостоверен`() {
        val last = realNow
        val now = last - MAX_CLOCK_BACKWARD_MS - 1_000L

        // Время всё ещё позже сборки: ловит именно проверка отката.
        assertTrue(now > buildTime)
        assertFalse(isClockPlausible(now, buildTime, last))
    }

    @Test
    fun `небольшой откат считается погрешностью`() {
        val last = realNow
        val now = last - 30L * 60L * 1000L

        assertTrue(isClockPlausible(now, buildTime, last))
    }

    @Test
    fun `без даты сборки и без прошлого времени часы достоверны`() {
        assertTrue(isClockPlausible(pretend2023, null, null))
        assertTrue(isClockPlausible(pretend2023, 0L, 0L))
    }

    @Test
    fun `без даты сборки откат всё равно ловится`() {
        assertFalse(isClockPlausible(pretend2023, null, realNow))
    }

    @Test
    fun `дата сборки разбирается из BuildConfig`() {
        assertEquals(buildTime, parseBuildDate("04.11.2026 22:56", utc))
    }

    @Test
    fun `некорректная дата сборки даёт null`() {
        assertNull(parseBuildDate(null, utc))
        assertNull(parseBuildDate("", utc))
        assertNull(parseBuildDate("2026-11-04T22:56", utc))
    }

    @Test
    fun `состояние часов инициализируется по проверке`() {
        ClockStatus.init(pretend2023, buildTime, realNow)
        assertFalse(ClockStatus.verified)
        assertEquals(realNow, ClockStatus.lastVerifiedWallMs)

        ClockStatus.onClockChanged(pretend2023 + 110_186_210_096L, 110_186_209_589L)
        assertTrue(ClockStatus.verified)
        assertEquals(110_186_209_589L, ClockStatus.shiftMs)

        ClockStatus.reset()
        assertTrue(ClockStatus.verified)
        assertEquals(0L, ClockStatus.shiftMs)
    }

    @Test
    fun `блок clock в снимке показывает непроверенные часы`() {
        ClockStatus.init(pretend2023, buildTime, realNow)

        val clock = ClockStatus.toJson(unverifiedEvents = 3)

        assertEquals(false, clock["verified"])
        assertEquals(realNow, clock["lastVerifiedAt"])
        assertEquals(3L, clock["unverifiedEvents"])

        ClockStatus.onClockChanged(realNow, 110_186_209_589L)
        val fixed = ClockStatus.toJson(unverifiedEvents = 3)
        assertEquals(true, fixed["verified"])
        assertEquals(realNow, fixed["verifiedAt"])
        assertEquals(110_186_209_589L, fixed["shiftMs"])

        ClockStatus.reset()
    }
}
