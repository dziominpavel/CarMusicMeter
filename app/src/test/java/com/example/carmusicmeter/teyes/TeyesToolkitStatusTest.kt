package com.example.carmusicmeter.teyes

import com.example.carmusicmeter.data.VolumeSelector
import com.example.carmusicmeter.data.VolumeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TeyesToolkitStatusTest {

    private fun live(volume: Int = 12, updatedAtMs: Long = 1_000L) = TeyesToolkitClient.Status(
        available = true,
        connected = true,
        module = true,
        volume = volume,
        muted = false,
        error = null,
        updatedAtMs = updatedAtMs,
    )

    @Test
    fun `изменение громкости не меняет сигнатуру toolkit`() {
        val first = TeyesToolkitClient.Status(
            available = true,
            connected = true,
            module = true,
            volume = 4,
            muted = false,
            error = null,
        )
        val second = first.copy(volume = 5, muted = true)

        assertEquals(first.signature, second.signature)
    }

    @Test
    fun `потеря модуля меняет сигнатуру toolkit`() {
        val first = TeyesToolkitClient.Status(
            available = true,
            connected = true,
            module = true,
            volume = 4,
            muted = false,
        )
        val second = first.copy(module = false, error = "sound module is null")

        assertNotEquals(first.signature, second.signature)
    }

    @Test
    fun `давно прочитанное значение MCU остаётся действительным`() {
        // Отметка updatedAtMs часовой давности: давность не повод менять шкалу.
        val reading = live(volume = 12, updatedAtMs = 0L).toMcuVolume()
        val selected = VolumeSelector.select(
            mcu = reading,
            audioValue = 25,
            audioMax = 25,
            mcuMax = 43,
        )

        assertEquals(12, reading.volume)
        assertEquals("mcu_available", reading.reason)
        assertEquals(VolumeSource.MCU, selected.source)
        assertEquals(12, selected.value)
        assertEquals(43, selected.max)
    }

    @Test
    fun `смерть binder переводит громкость на резервный источник`() {
        val dead = TeyesToolkitClient.Status(
            available = false,
            connected = false,
            module = false,
            volume = null,
            muted = null,
            error = "toolkit binding died",
        )
        val selected = VolumeSelector.select(
            mcu = dead.toMcuVolume(),
            audioValue = 25,
            audioMax = 25,
            mcuMax = 43,
        )

        assertEquals("mcu_unavailable", dead.toMcuVolume().reason)
        assertEquals(VolumeSource.AUDIO, selected.source)
        assertEquals("mcu_unavailable", selected.reason)
    }

    @Test
    fun `возврат сервиса снова отдаёт громкость MCU`() {
        val selected = VolumeSelector.select(
            mcu = live(volume = 7).toMcuVolume(),
            audioValue = 25,
            audioMax = 25,
            mcuMax = 43,
        )

        assertEquals(VolumeSource.MCU, selected.source)
        assertEquals("mcu_available", selected.reason)
        assertEquals(7, selected.value)
    }
}
