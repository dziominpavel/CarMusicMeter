package com.example.carmusicmeter.teyes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TeyesToolkitStatusTest {

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
}
