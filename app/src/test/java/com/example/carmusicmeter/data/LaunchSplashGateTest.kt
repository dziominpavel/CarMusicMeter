package com.example.carmusicmeter.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LaunchSplashGateTest {

    @Before
    fun resetGate() {
        LaunchSplashGate.reset()
    }

    @Test
    fun firstLaunchInProcessShowsSplash() {
        assertTrue(LaunchSplashGate.shouldShow())
    }

    @Test
    fun secondLaunchInSameProcessSkipsSplash() {
        LaunchSplashGate.shouldShow()

        assertFalse(LaunchSplashGate.shouldShow())
    }

    @Test
    fun newProcessShowsSplashAgain() {
        LaunchSplashGate.shouldShow()
        LaunchSplashGate.reset()

        assertTrue(LaunchSplashGate.shouldShow())
    }
}
