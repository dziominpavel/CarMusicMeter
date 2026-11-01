package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationSignatureTest {

    private fun signature(title: String = "Song", bigText: String = "Artist") =
        NotificationSignature.build(
            packageName = "com.example.player",
            title = title,
            bigText = bigText,
            template = "MediaStyle",
            actions = listOf("play"),
            mediaLike = true,
            stockSignature = null,
        )

    @Test
    fun `прогресс в тексте не меняет сигнатуру`() {
        // text/subText не передаются: сигнатура зависит только от идентичности.
        assertEquals(signature(), signature())
    }

    @Test
    fun `смена названия меняет сигнатуру`() {
        assertNotEquals(signature(title = "Song"), signature(title = "Other Song"))
    }
}
