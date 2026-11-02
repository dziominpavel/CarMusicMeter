package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoSourceGapTrackerTest {

    @Test
    fun `граница разрыва пишется один раз`() {
        val tracker = NoSourceGapTracker()

        assertNull(tracker.update(false))
        assertEquals(true, tracker.update(true))
        assertTrue(tracker.isActive())
        assertNull(tracker.update(true))
        assertEquals(false, tracker.update(false))
        assertFalse(tracker.isActive())
        assertNull(tracker.update(false))
    }
}
