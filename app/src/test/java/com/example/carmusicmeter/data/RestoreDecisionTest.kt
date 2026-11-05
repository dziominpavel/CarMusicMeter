package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreDecisionTest {

    private val key = "com.syu.music::wake me up::avicii"

    @Test
    fun `тот же трек продолжает прослушивание`() {
        assertEquals(
            RestoreDecision.CONTINUE,
            decideRestore(key, key, elapsedMs = 500L),
        )
    }

    @Test
    fun `другой трек закрывает прослушивание`() {
        assertEquals(
            RestoreDecision.CLOSE,
            decideRestore(key, "com.syu.music::try::ai mori", elapsedMs = 500L),
        )
    }

    @Test
    fun `без метаданных ждём в пределах окна`() {
        assertEquals(
            RestoreDecision.WAIT,
            decideRestore(key, null, elapsedMs = 9_000L),
        )
    }

    @Test
    fun `без метаданных закрываем после окна`() {
        assertEquals(
            RestoreDecision.CLOSE,
            decideRestore(key, null, elapsedMs = 10_001L),
        )
    }

    @Test
    fun `свой порог окна соблюдается`() {
        assertEquals(RestoreDecision.WAIT, decideRestore(key, null, elapsedMs = 10_000L))
        assertEquals(RestoreDecision.CLOSE, decideRestore(key, null, elapsedMs = 600L, graceMs = 500L))
    }
}
