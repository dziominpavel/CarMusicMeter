package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClockJumpTest {

    @Test
    fun `скачок от 2023 года к реальному времени определяется по расхождению`() {
        // Значения из журнала 2026-11-05: часы магнитолы стояли на 2023-05-10
        // и исправились по NTP, пока служба уже работала.
        val jump = ClockJumpDetector.detect(
            previousWallMs = 1_683_706_439_724L,
            previousMonoMs = 62_258L,
            wallMs = 1_793_892_693_340L,
            monoMs = 62_765L,
        )

        assertNotNull(jump)
        assertEquals(110_186_253_616L, jump!!.wallDeltaMs)
        assertEquals(507L, jump.monoDeltaMs)
        assertEquals(110_186_253_109L, jump.driftMs)
    }

    @Test
    fun `обычный тик не считается скачком`() {
        val jump = ClockJumpDetector.detect(
            previousWallMs = 1_793_892_693_340L,
            previousMonoMs = 62_000L,
            wallMs = 1_793_892_693_840L,
            monoMs = 62_500L,
        )

        assertNull(jump)
    }

    @Test
    fun `без предыдущего тика скачка нет`() {
        assertNull(
            ClockJumpDetector.detect(
                previousWallMs = null,
                previousMonoMs = null,
                wallMs = 1_793_892_693_340L,
                monoMs = 62_765L,
            ),
        )
        assertNull(
            ClockJumpDetector.detect(
                previousWallMs = 1_793_892_693_340L,
                previousMonoMs = null,
                wallMs = 1_793_892_693_340L,
                monoMs = 62_765L,
            ),
        )
    }
}
