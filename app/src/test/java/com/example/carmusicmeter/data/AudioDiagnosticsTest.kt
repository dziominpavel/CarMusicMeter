package com.example.carmusicmeter.data

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDiagnosticsTest {

    @Test
    fun `снимок содержит все аудиопотоки и флаги`() {
        val snapshot = AudioDiagnostics.snapshot(
            volume = { it + 1 },
            max = { 15 },
            isVolumeFixed = true,
            audioMode = AudioManager.MODE_NORMAL,
            isMusicActive = false,
        )

        val volumes = snapshot["volumes"] as List<*>
        assertEquals(AudioDiagnostics.STREAMS.size, volumes.size)
        assertEquals(
            listOf(
                "music",
                "voice_call",
                "system",
                "notification",
                "alarm",
                "ring",
                "accessibility",
            ),
            volumes.map { (it as Map<*, *>)["name"] },
        )

        val music = volumes.first() as Map<*, *>
        assertEquals(AudioManager.STREAM_MUSIC + 1, music["value"])
        assertEquals(15, music["max"])

        assertTrue(snapshot["isVolumeFixed"] as Boolean)
        assertEquals(AudioManager.MODE_NORMAL, snapshot["audioMode"])
        assertFalse(snapshot["isMusicActive"] as Boolean)
    }

    @Test
    fun `индексы потоков уникальны`() {
        val indexes = AudioDiagnostics.STREAMS.map { it.index }

        assertEquals(indexes.size, indexes.distinct().size)
    }
}
