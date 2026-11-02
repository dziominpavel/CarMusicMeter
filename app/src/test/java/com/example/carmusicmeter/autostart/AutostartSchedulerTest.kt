package com.example.carmusicmeter.autostart

import com.example.carmusicmeter.service.StartSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutostartSchedulerTest {

    @Test
    fun `watchdog и разовый job различаются по id`() {
        assertEquals(
            StartSource.WATCHDOG,
            AutostartScheduler.sourceForJob(AutostartScheduler.JOB_ID_WATCHDOG),
        )
        assertEquals(
            StartSource.JOB,
            AutostartScheduler.sourceForJob(AutostartScheduler.JOB_ID_ONESHOT),
        )
    }

    @Test
    fun `работающую службу watchdog не запускает`() {
        assertTrue(AutostartScheduler.shouldStart(autoStart = true, serviceRunning = false))
        assertFalse(AutostartScheduler.shouldStart(autoStart = true, serviceRunning = true))
        assertFalse(AutostartScheduler.shouldStart(autoStart = false, serviceRunning = false))
    }
}
