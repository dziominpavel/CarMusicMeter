package com.example.carmusicmeter.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {

    @Test
    fun `sprd manufacturer is a Teyes unit`() {
        assertTrue(DeviceProfile.isTeyes("sprd", "unknown"))
        assertTrue(DeviceProfile.isTeyes("SPRD", "unknown"))
    }

    @Test
    fun `ums512 model is a Teyes unit`() {
        assertTrue(DeviceProfile.isTeyes("unknown", "ums512_1h10"))
        assertTrue(DeviceProfile.isTeyes("unknown", "UMS512"))
    }

    @Test
    fun `ordinary phone is not a Teyes unit`() {
        assertFalse(DeviceProfile.isTeyes("Google", "Pixel 7"))
        assertFalse(DeviceProfile.isTeyes(null, null))
        assertFalse(DeviceProfile.isTeyes("", ""))
    }
}
