package com.example.carmusicmeter.data

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityConnectionTest {

    @After
    fun tearDown() {
        AccessibilityConnection.reset()
    }

    @Test
    fun `heartbeat живёт десять секунд`() {
        AccessibilityConnection.onConnected(1_000L)

        assertTrue(AccessibilityConnection.isConnected(11_000L))
        assertFalse(AccessibilityConnection.isConnected(11_001L))
    }

    @Test
    fun `скан продлевает heartbeat`() {
        AccessibilityConnection.onConnected(1_000L)
        AccessibilityConnection.onScan(8_000L)

        assertTrue(AccessibilityConnection.isConnected(18_000L))
        assertFalse(AccessibilityConnection.isConnected(18_001L))
    }

    @Test
    fun `отключение сбрасывает состояние`() {
        AccessibilityConnection.onConnected(1_000L)
        AccessibilityConnection.onDisconnected()

        assertFalse(AccessibilityConnection.isConnected(2_000L))
    }

    @Test
    fun `скан без подключения не включает сервис`() {
        AccessibilityConnection.onScan(1_000L)

        assertFalse(AccessibilityConnection.isConnected(2_000L))
    }
}
