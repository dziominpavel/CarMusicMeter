package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StockPlayerStateTest {

    private fun state(
        playing: Boolean = true,
        playingKnown: Boolean = true,
        source: String = StockPlayerSources.FYT_BROADCAST,
        updatedAtMs: Long = 1_000L,
        updatedAtMonoMs: Long = 5_000L,
    ) = StockPlayerState(
        packageName = "com.yf.teyesspotify",
        title = "Song",
        artist = "Artist",
        playing = playing,
        playingKnown = playingKnown,
        source = source,
        updatedAtMs = updatedAtMs,
        updatedAtMonoMs = updatedAtMonoMs,
    )

    @Test
    fun `свежее состояние превращается в снимок`() {
        assertNotNull(state().toSnapshot(nowMonoMs = 5_500L, isMusicActive = true))
    }

    @Test
    fun `пауза тридцать секунд не теряет трек`() {
        assertEquals(
            MediaPhase.PAUSED,
            state(playing = false).toSnapshot(nowMonoMs = 35_000L, isMusicActive = false)?.phase,
        )
        assertNull(state(playing = false).toSnapshot(nowMonoMs = 11 * 60_000L, isMusicActive = false))
    }

    @Test
    fun `broadcast без play state использует isMusicActive`() {
        val value = state(playing = false, playingKnown = false)

        assertEquals(MediaPhase.PLAYING, value.toSnapshot(20_000L, isMusicActive = true)?.phase)
        assertEquals(MediaPhase.PAUSED, value.toSnapshot(20_000L, isMusicActive = false)?.phase)
    }

    @Test
    fun `fyt_service истекает за тридцать секунд при игре`() {
        val value = state(source = StockPlayerSources.FYT_SERVICE, updatedAtMonoMs = 0L)

        assertNotNull(value.toSnapshot(29_999L, isMusicActive = true))
        assertNull(value.toSnapshot(30_001L, isMusicActive = true))
    }

    @Test
    fun `fyt_service на паузе живёт десять минут`() {
        val value = state(
            playing = false,
            source = StockPlayerSources.FYT_SERVICE,
            updatedAtMonoMs = 0L,
        )

        assertNotNull(value.toSnapshot(9 * 60_000L, isMusicActive = false))
        assertNull(value.toSnapshot(10 * 60_000L + 1L, isMusicActive = false))
    }

    @Test
    fun `fyt_service без play state использует isMusicActive`() {
        val value = state(
            playing = false,
            playingKnown = false,
            source = StockPlayerSources.FYT_SERVICE,
        )

        assertEquals(MediaPhase.PLAYING, value.toSnapshot(20_000L, isMusicActive = true)?.phase)
        assertEquals(MediaPhase.PAUSED, value.toSnapshot(20_000L, isMusicActive = false)?.phase)
    }

    @Test
    fun `уведомление не считается играющим без активной музыки`() {
        val value = state(
            playing = true,
            playingKnown = false,
            source = StockPlayerSources.NOTIFICATION,
        )

        assertEquals(MediaPhase.PAUSED, value.toSnapshot(6_000L, isMusicActive = false)?.phase)
        assertEquals(MediaPhase.PLAYING, value.toSnapshot(6_000L, isMusicActive = true)?.phase)
    }

    @Test
    fun `возраст считается по монотонному времени`() {
        val value = state(updatedAtMs = 1_000L, updatedAtMonoMs = 5_000L)
        // Настенные часы здесь не участвуют: снимок остаётся свежим.
        assertNotNull(value.toSnapshot(nowMonoMs = 10_000L, isMusicActive = true))
    }

    @Test
    fun `монотонная отметка сохраняется в модели`() {
        assertEquals(5_000L, state().updatedAtMonoMs)
    }

    @Test
    fun `видимое окно touch не даёт accessibility истечь за 15 минут`() {
        StockPlayerBridge.reset()
        val value = state(source = StockPlayerSources.ACCESSIBILITY, updatedAtMonoMs = 0L)
        StockPlayerBridge.update(value)
        StockPlayerBridge.touch(15 * 60_000L)

        assertNotNull(StockPlayerBridge.snapshot(15 * 60_000L + 1_000L, isMusicActive = true))
        StockPlayerBridge.reset()
    }

    @Test
    fun `потеря окна истекает accessibility за 90 секунд`() {
        val value = state(source = StockPlayerSources.ACCESSIBILITY, updatedAtMonoMs = 0L)

        assertNull(value.toSnapshot(90_001L, isMusicActive = true))
        assertNotNull(value.toSnapshot(89_999L, isMusicActive = true))
    }
}
