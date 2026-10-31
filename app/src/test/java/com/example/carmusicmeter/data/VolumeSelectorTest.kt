package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeSelectorTest {

    @Test
    fun `MCU важнее AudioManager и не подставляет чужой максимум`() {
        val reading = VolumeSelector.select(
            mcu = McuVolume(volume = 4, muted = false, updatedAtMs = 1),
            audioValue = 25,
            audioMax = 25,
        )

        assertEquals(4, reading.value)
        assertNull(reading.max)
        assertEquals(VolumeSource.MCU, reading.source)
    }

    @Test
    fun `mute даёт ноль и признак muted`() {
        val reading = VolumeSelector.select(
            mcu = McuVolume(volume = 4, muted = true, updatedAtMs = 1),
            audioValue = 25,
            audioMax = 25,
        )

        assertEquals(0, reading.value)
        assertTrue(reading.muted)
        assertEquals(VolumeSource.MCU, reading.source)
    }

    @Test
    fun `без MCU используется AudioManager`() {
        val reading = VolumeSelector.select(null, 25, 25)

        assertEquals(25, reading.value)
        assertEquals(25, reading.max)
        assertEquals(VolumeSource.AUDIO, reading.source)
    }

    @Test
    fun `MCU без значения откатывается на AudioManager`() {
        val reading = VolumeSelector.select(
            McuVolume(volume = null, muted = null, updatedAtMs = 1),
            7,
            25,
        )

        assertEquals(7, reading.value)
        assertEquals(25, reading.max)
        assertEquals(VolumeSource.AUDIO, reading.source)
    }
}
