package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourcePickerTest {

    private fun snapshot(
        packageName: String,
        title: String,
        phase: MediaPhase,
        source: String = StockPlayerSources.MEDIA_SESSION,
    ) = MediaSnapshot(
        packageName = packageName,
        title = title,
        phase = phase,
        source = source,
    )

    @Test
    fun `играющая MediaSession важнее играющего штатного плеера`() {
        val media = snapshot("ru.yandex.music", "Yandex", MediaPhase.PLAYING)
        val stock = snapshot(
            "com.yf.teyesspotify",
            "Stock",
            MediaPhase.PLAYING,
            StockPlayerSources.FYT_BROADCAST,
        )

        assertEquals(media, SourcePicker.pick(listOf(media), stock, "com.yf.teyesspotify"))
    }

    @Test
    fun `играющий штатный плеер важнее паузы MediaSession`() {
        val media = snapshot("ru.yandex.music", "Yandex", MediaPhase.PAUSED)
        val stock = snapshot(
            "com.yf.teyesspotify",
            "Stock",
            MediaPhase.PLAYING,
            StockPlayerSources.FYT_BROADCAST,
        )

        assertEquals(stock, SourcePicker.pick(listOf(media), stock, "ru.yandex.music"))
    }

    @Test
    fun `без MediaSession выбирается штатный плеер`() {
        val stock = snapshot(
            "com.yf.teyesspotify",
            "Stock",
            MediaPhase.PAUSED,
            StockPlayerSources.ACCESSIBILITY,
        )

        assertEquals(stock, SourcePicker.pick(emptyList(), stock, null))
    }

    @Test
    fun `пауза текущего штатного плеера важнее чужой паузы`() {
        val media = snapshot("ru.yandex.music", "Yandex", MediaPhase.PAUSED)
        val stock = snapshot(
            "com.yf.teyesspotify",
            "Stock",
            MediaPhase.PAUSED,
            StockPlayerSources.FYT_BROADCAST,
        )

        assertEquals(stock, SourcePicker.pick(listOf(media), stock, "com.yf.teyesspotify"))
    }

    @Test
    fun `без штатного плеера работает прежний выбор MediaSession`() {
        val media = snapshot("ru.yandex.music", "Yandex", MediaPhase.PLAYING)
        assertEquals(media, SourcePicker.pick(listOf(media), null, null))
        assertNull(SourcePicker.pick(emptyList(), null, null))
    }
}
