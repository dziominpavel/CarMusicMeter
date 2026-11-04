package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityWindowTest {

    private fun window(
        packageName: String = "com.syu.music",
        title: String? = null,
        active: Boolean = false,
        focused: Boolean = false,
        text: String = "Song",
    ) = AccessibilityWindow(
        packageName = packageName,
        title = title,
        active = active,
        focused = focused,
        nodes = listOf(AccessibilityNode(text = text)),
    )

    @Test
    fun `активное окно важнее сфокусированного`() {
        val selected = AccessibilityWindowSelector.select(
            listOf(
                window(active = false, focused = true, text = "focused"),
                window(active = true, focused = false, text = "active"),
            ),
        )

        assertEquals("active", selected?.texts()?.first())
    }

    @Test
    fun `split screen выбирает окно плеера среди окон`() {
        val selected = AccessibilityWindowSelector.select(
            listOf(
                window(packageName = "com.syu.music", active = false, focused = false, text = "music"),
                window(packageName = "com.syu.music", active = true, focused = true, text = "active music"),
            ),
        )

        assertEquals("active music", selected?.texts()?.first())
    }

    @Test
    fun `debounce пропускает подпись только после 800 мс`() {
        val gate = DebounceGate(800)

        assertFalse(gate.offer("a", 0))
        assertFalse(gate.offer("a", 799))
        assertTrue(gate.offer("a", 800))
        assertFalse(gate.offer("b", 801))
        assertTrue(gate.offer("b", 1_601))
    }

    @Test
    fun `throttle не пишет одинаковую подпись и держит интервал`() {
        val throttle = UiLogThrottle(2_000)

        assertTrue(throttle.shouldLog("a", 0))
        assertFalse(throttle.shouldLog("a", 100))
        assertFalse(throttle.shouldLog("b", 1_000))
        assertTrue(throttle.shouldLog("b", 2_000))
        assertFalse(throttle.shouldLog("b", 2_100))
    }

    @Test
    fun `json окна содержит тексты узлы и rangeInfo`() {
        val json = AccessibilityWindow(
            packageName = "com.syu.music",
            title = "Player",
            active = true,
            nodes = listOf(
                AccessibilityNode(
                    className = "android.widget.SeekBar",
                    viewId = "com.syu.music:id/progress",
                    text = "The Best.flac",
                    contentDescription = "progress",
                    range = AccessibilityRange(0f, 180_000f, 30_000f),
                    bounds = "0,0,100,20",
                ),
            ),
        ).toJson()

        assertEquals("com.syu.music", json.getString("package"))
        assertEquals("Player", json.getString("title"))
        assertTrue(json.getBoolean("active"))
        assertEquals("The Best.flac", json.getJSONArray("texts").getString(0))
        val node = json.getJSONArray("nodes").getJSONObject(0)
        assertEquals("android.widget.SeekBar", node.getString("className"))
        assertEquals(180_000.0, node.getJSONObject("range").getDouble("max"), 0.0)
    }

    @Test
    fun `selector игнорирует окно без узлов`() {
        assertNull(
            AccessibilityWindowSelector.select(
                listOf(AccessibilityWindow(packageName = "com.syu.music", nodes = emptyList())),
            ),
        )
    }

    @Test
    fun `переходный Список не проходит debounce а стабильный трек проходит`() {
        val transient = AccessibilityWindow(
            packageName = "com.syu.music",
            nodes = listOf(
                AccessibilityNode(text = "Список"),
                AccessibilityNode(text = "1763. Tina Turner - The Best.flac"),
            ),
        )
        val stable = AccessibilityWindow(
            packageName = "com.syu.music",
            nodes = listOf(
                AccessibilityNode(text = "Tina Turner - The Best.flac"),
                AccessibilityNode(text = "Список"),
                AccessibilityNode(text = "Tina Turner"),
            ),
        )
        val transientResult = StockPlayerParser.inspectAccessibility(
            "com.syu.music", transient, playing = true, nowMs = 0L,
        )
        val stableResult = StockPlayerParser.inspectAccessibility(
            "com.syu.music", stable, playing = true, nowMs = 0L,
        )
        val gate = DebounceGate(800)

        assertFalse(transientResult.accepted)
        assertFalse(gate.offer(transient.signature(), 0))
        assertFalse(gate.offer(stable.signature(), 400))
        assertTrue(gate.offer(stable.signature(), 1_200))
        assertTrue(stableResult.accepted)
        assertEquals("The Best", stableResult.state?.title)
    }

    private fun playerWindow(position: String, rangeCurrent: Float, title: String = "The Best") =
        AccessibilityWindow(
            packageName = "com.syu.music",
            title = "Музыка",
            nodes = listOf(
                AccessibilityNode(className = "android.widget.TextView", text = title),
                AccessibilityNode(className = "android.widget.TextView", text = "Tina Turner"),
                AccessibilityNode(className = "android.widget.TextView", text = position),
                AccessibilityNode(
                    className = "android.widget.SeekBar",
                    viewId = "com.syu.music:id/progress",
                    range = AccessibilityRange(0f, 180_000f, rangeCurrent),
                ),
            ),
        )

    @Test
    fun `диагностическая подпись не зависит от бегущей позиции`() {
        val first = playerWindow(position = "00:30", rangeCurrent = 30_000f)
        val second = playerWindow(position = "00:35", rangeCurrent = 35_000f)

        assertEquals(first.diagnosticsSignature(), second.diagnosticsSignature())
        assertTrue(first.signature() != second.signature())
    }

    @Test
    fun `диагностическая подпись меняется при смене трека`() {
        val first = playerWindow(position = "00:30", rangeCurrent = 30_000f, title = "The Best")
        val second = playerWindow(position = "00:30", rangeCurrent = 30_000f, title = "Proud Mary")

        assertTrue(first.diagnosticsSignature() != second.diagnosticsSignature())
    }

    @Test
    fun `подпись диагностики учитывает решение парсера но не позицию`() {
        val first = playerWindow(position = "00:30", rangeCurrent = 30_000f)
        val second = playerWindow(position = "00:35", rangeCurrent = 35_000f)
        val firstResult = StockPlayerParser.inspectAccessibility(
            "com.syu.music", first, playing = true, nowMs = 0L,
        )
        val secondResult = StockPlayerParser.inspectAccessibility(
            "com.syu.music", second, playing = true, nowMs = 0L,
        )

        assertEquals(uiDiagnosticsSignature(first, firstResult), uiDiagnosticsSignature(second, secondResult))
    }

    @Test
    fun `throttle переписывает неизменную подпись раз в пять минут`() {
        val throttle = UiLogThrottle(minIntervalMs = 2_000, anchorIntervalMs = 5 * 60_000)
        var written = 0
        var now = 0L
        val twentyMinutes = 20 * 60_000L

        while (now < twentyMinutes) {
            if (throttle.shouldLog("same", now)) written += 1
            now += 1_000
        }

        assertEquals(4, written)
    }
}
