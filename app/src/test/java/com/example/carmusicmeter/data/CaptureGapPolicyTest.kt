package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureGapPolicyTest {

    @Test
    fun `скрытое окно при активном источнике не открывает gap`() {
        assertNull(CaptureGapPolicy.action(musicActive = true, gapActive = false, sourceActive = true))
    }

    @Test
    fun `активный источник закрывает ранее открытый gap`() {
        assertEquals(
            CaptureGapPolicy.Action.CLOSE_SOURCE_AVAILABLE,
            CaptureGapPolicy.action(musicActive = true, gapActive = true, sourceActive = true),
        )
    }

    @Test
    fun `без источника gap открывается`() {
        assertEquals(
            CaptureGapPolicy.Action.OPEN_WINDOW_UNAVAILABLE,
            CaptureGapPolicy.action(musicActive = true, gapActive = false, sourceActive = false),
        )
    }

    @Test
    fun `музыка остановилась закрывает gap`() {
        assertEquals(
            CaptureGapPolicy.Action.CLOSE_MUSIC_INACTIVE,
            CaptureGapPolicy.action(musicActive = false, gapActive = true, sourceActive = false),
        )
    }

    @Test
    fun `без музыки и без gap ничего не делает`() {
        assertNull(CaptureGapPolicy.action(musicActive = false, gapActive = false, sourceActive = false))
    }
}
