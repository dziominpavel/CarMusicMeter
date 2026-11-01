package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeSelectorTest {

    @Test
    fun `MCU предпочтительнее AudioManager и имеет максимум 43`() {
        val reading = VolumeSelector.select(
            mcu = McuVolume(volume = 4, muted = false, updatedAtMs = 1),
            audioValue = 25,
            audioMax = 25,
        )

        assertEquals(4, reading.value)
        assertEquals(43, reading.max)
        assertEquals(VolumeSource.MCU, reading.source)
        assertEquals("mcu_available", reading.reason)
    }

    @Test
    fun `явно отключенный максимум MCU остается null`() {
        val reading = VolumeSelector.select(
            mcu = McuVolume(volume = 30, muted = false, updatedAtMs = 1),
            audioValue = 25,
            audioMax = 25,
            mcuMax = 0,
        )

        assertEquals(30, reading.value)
        assertNull(reading.max)
        assertEquals(VolumeSource.MCU, reading.source)
    }

    @Test
    fun `mute сохраняет абсолютную громкость и флаг muted`() {
        val reading = VolumeSelector.select(
            mcu = McuVolume(volume = 4, muted = true, updatedAtMs = 1),
            audioValue = 25,
            audioMax = 25,
        )

        assertEquals(4, reading.value)
        assertTrue(reading.muted)
        assertEquals(VolumeSource.MCU, reading.source)
    }

    @Test
    fun `без MCU используется AudioManager`() {
        val reading = VolumeSelector.select(null, 25, 25)

        assertEquals(25, reading.value)
        assertEquals(25, reading.max)
        assertEquals(VolumeSource.AUDIO, reading.source)
        assertEquals("audio_fallback", reading.reason)
    }

    @Test
    fun `MCU без значения не подменяет AudioManager`() {
        val reading = VolumeSelector.select(
            McuVolume(volume = null, muted = null, updatedAtMs = 1),
            7,
            25,
        )

        assertEquals(7, reading.value)
        assertEquals(25, reading.max)
        assertEquals(VolumeSource.AUDIO, reading.source)
    }

    @Test
    fun `stale MCU дает причину mcu_stale`() {
        val reading = VolumeSelector.select(
            McuVolume(volume = null, muted = null, updatedAtMs = 1, reason = "mcu_stale"),
            7,
            25,
        )

        assertEquals(VolumeSource.AUDIO, reading.source)
        assertEquals("mcu_stale", reading.reason)
    }

    @Test
    fun `телефон без MCU дает audio_fallback`() {
        val reading = VolumeSelector.select(null, 7, 25, fallbackReason = "audio_fallback")

        assertEquals("audio_fallback", reading.reason)
    }

    @Test
    fun `недоступный и сломанный MCU различаются причиной`() {
        val unavailable = VolumeSelector.select(
            McuVolume(volume = null, muted = null, updatedAtMs = 1, reason = "mcu_unavailable"),
            7,
            25,
        )
        val error = VolumeSelector.select(
            McuVolume(volume = null, muted = null, updatedAtMs = 1, reason = "mcu_error"),
            7,
            25,
        )

        assertEquals("mcu_unavailable", unavailable.reason)
        assertEquals("mcu_error", error.reason)
    }
}
