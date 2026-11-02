package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsStatusTest {

    @Test
    fun `статус listener не путается с разрешением`() {
        NotificationListenerStatus.connected = false
        assertFalse(NotificationListenerStatus.connected)

        NotificationListenerStatus.connected = true
        assertTrue(NotificationListenerStatus.connected)
    }

    @Test
    fun `снимок различает разрешение и подключение listener`() {
        val offline = SnapshotDiagnostics.notificationFlags(
            accessGranted = true,
            listenerConnected = false,
        )
        val online = SnapshotDiagnostics.notificationFlags(
            accessGranted = true,
            listenerConnected = true,
        )

        assertEquals(true, offline["notificationAccess"])
        assertEquals(false, offline["notificationListenerConnected"])
        assertEquals(true, online["notificationListenerConnected"])
    }

    @Test
    fun `StockPlayerUiStore сохраняет окно и решение парсера`() {
        StockPlayerUiStore.reset()
        StockPlayerUiStore.update(
            window = AccessibilityWindow(
                packageName = "com.syu.music",
                title = "Player",
                nodes = listOf(AccessibilityNode(text = "The Best.flac")),
            ),
            accepted = true,
            rejectReason = null,
            confidence = "high",
            title = "The Best",
            artist = "Tina Turner",
            durationMs = 225_000L,
            positionMs = 30_000L,
        )

        val json = StockPlayerUiStore.toJson()

        assertTrue(json.getBoolean("accepted"))
        assertEquals("high", json.getString("confidence"))
        assertEquals("The Best", json.getString("title"))
        assertEquals(225_000L, json.getLong("durationMs"))
        assertEquals("com.syu.music", json.getJSONObject("window").getString("package"))
        StockPlayerUiStore.reset()
    }

    @Test
    fun `stock_player_ui содержит все тексты узлы и rangeInfo`() {
        val window = AccessibilityWindow(
            packageName = "com.syu.music",
            title = "Player",
            nodes = listOf(
                AccessibilityNode(
                    className = "android.widget.SeekBar",
                    viewId = "com.syu.music:id/progress",
                    text = "The Best.flac",
                    range = AccessibilityRange(0f, 180_000f, 30_000f),
                ),
            ),
        )
        val result = StockPlayerParser.inspectAccessibility(
            packageName = "com.syu.music",
            window = window,
            playing = true,
            nowMs = 1L,
        )
        val fields = StockPlayerUiEvent(window, listOf(window), result).toFields()

        assertEquals(1, (fields["nodes"] as List<*>).size)
        assertEquals(1, (fields["windows"] as List<*>).size)
        assertEquals(true, fields["accepted"])
        assertEquals("The Best", fields["title"])
        val node = (fields["nodes"] as List<*>).first() as Map<*, *>
        assertEquals("com.syu.music:id/progress", node["viewId"])
        assertEquals(180_000f, (node["range"] as Map<*, *>)["max"])
    }

    @Test
    fun `stock_player_ui ограничивает число узлов шестьюдесятью`() {
        val window = AccessibilityWindow(
            packageName = "com.syu.music",
            nodes = (1..70).map { AccessibilityNode(text = "node $it") },
        )
        val result = StockPlayerParser.inspectAccessibility(
            packageName = "com.syu.music",
            window = window,
            playing = true,
            nowMs = 1L,
        )

        val fields = StockPlayerUiEvent(window, listOf(window), result).toFields()

        assertEquals(60, (fields["nodes"] as List<*>).size)
    }

    @Test
    fun `capture_gap без последнего трека пишет null`() {
        val fields = CaptureGap(
            active = true,
            reason = "no_source",
            last = null,
            isMusicActive = true,
            windows = emptyList(),
        ).toFields()

        assertEquals(true, fields["active"])
        assertEquals("no_source", fields["reason"])
        assertNull(fields["lastTitle"])
        assertNull(fields["lastArtist"])
    }

    @Test
    fun `capture_gap содержит последний трек и список окон`() {
        val fields = CaptureGap(
            active = true,
            reason = "stock_window_unavailable",
            last = StockPlayerState(
                packageName = "com.syu.music",
                title = "The Best",
                artist = "Tina Turner",
                source = StockPlayerSources.ACCESSIBILITY,
                updatedAtMs = 1L,
            ),
            isMusicActive = true,
            windows = listOf(
                AccessibilityWindow(
                    packageName = "com.syu.music",
                    active = false,
                    nodes = listOf(AccessibilityNode(text = "The Best")),
                ),
            ),
        ).toFields()

        assertEquals(true, fields["active"])
        assertEquals("stock_window_unavailable", fields["reason"])
        assertEquals("The Best", fields["lastTitle"])
        assertEquals(true, fields["isMusicActive"])
        assertEquals(1, (fields["windows"] as List<*>).size)
    }
}
