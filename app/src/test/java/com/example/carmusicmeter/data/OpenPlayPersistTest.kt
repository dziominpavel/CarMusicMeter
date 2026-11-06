package com.example.carmusicmeter.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenPlayPersistTest {

    private fun event(type: String) = LogEvent(ts = 1_793_973_000_000, type = type, fields = emptyMap())

    @Test
    fun `окончание трека стирает сохранённое прослушивание`() {
        assertTrue(shouldPersistOpenPlay(listOf(event("track_ended")), checkpoint = null))
    }

    @Test
    fun `начало трека сохраняет прослушивание`() {
        assertTrue(shouldPersistOpenPlay(listOf(event("track_started")), checkpoint = null))
    }

    @Test
    fun `контрольная точка сохраняет прослушивание`() {
        assertTrue(shouldPersistOpenPlay(emptyList(), checkpoint = event("track_progress")))
    }

    @Test
    fun `переход трека сохраняет новое прослушивание`() {
        assertTrue(
            shouldPersistOpenPlay(
                listOf(event("track_ended"), event("track_started")),
                checkpoint = null,
            ),
        )
    }

    @Test
    fun `громкость и источники настройки не трогают`() {
        assertFalse(
            shouldPersistOpenPlay(
                listOf(event("volume_changed"), event("volume_source_changed"), event("seek")),
                checkpoint = null,
            ),
        )
    }

    @Test
    fun `пустая пачка ничего не пишет`() {
        assertFalse(shouldPersistOpenPlay(emptyList(), checkpoint = null))
    }
}
