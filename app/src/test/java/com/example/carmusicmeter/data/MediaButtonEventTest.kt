package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaButtonEventTest {

    @Test
    fun `next и previous разбираются в понятные имена`() {
        val next = MediaButtonEvent.fromKey(87, 0, 1_000L, 0, "com.syu.ms")!!
        val previous = MediaButtonEvent.fromKey(88, 1, 2_000L, 1)!!

        assertEquals("media_next", next.keyName)
        assertEquals("down", next.action)
        assertEquals("com.syu.ms", next.sourcePackage)
        assertEquals("media_previous", previous.keyName)
        assertEquals("up", previous.action)
        assertEquals(1, previous.repeatCount)
    }

    @Test
    fun `play pause разбирается`() {
        val event = MediaButtonEvent.fromKey(85, 0, 3_000L, 0)!!

        assertEquals("media_play_pause", event.keyName)
    }

    @Test
    fun `неизвестное действие не создаёт событие`() {
        assertNull(MediaButtonEvent.fromKey(87, 2, 1_000L, 0))
    }

    @Test
    fun `событие содержит текущий трек или null`() {
        val event = MediaButtonEvent.fromKey(87, 0, 1_000L, 0)!!

        val fields = event.toLogEvent(5_000L, live = null).fields

        assertEquals("media_button", event.toLogEvent(5_000L, null).type)
        assertEquals(87, fields["keyCode"])
        assertEquals("media_next", fields["key"])
        assertEquals("down", fields["action"])
        assertNull(fields["trackKey"])
        assertNull(fields["title"])
    }
}
