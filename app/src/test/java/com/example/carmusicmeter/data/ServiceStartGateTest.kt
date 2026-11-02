package com.example.carmusicmeter.data

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStartGateTest {

    @After
    fun tearDown() {
        ServiceProcessInfo.reset()
    }

    @Test
    fun `повторный старт в том же экземпляре не логируется`() {
        val gate = ServiceStartGate()

        assertTrue(gate.shouldLog())
        assertFalse(gate.shouldLog())

        gate.reset()
        assertTrue(gate.shouldLog())
    }

    @Test
    fun `новый процесс определяется по pid`() {
        ServiceProcessInfo.reset()

        assertTrue(ServiceProcessInfo.newProcess(100))
        assertFalse(ServiceProcessInfo.newProcess(100))
        assertTrue(ServiceProcessInfo.newProcess(101))
    }
}
