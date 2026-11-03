package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStatusTest {

    private val now = 1_000_000L

    private fun state(
        serviceRunning: Boolean = true,
        lastSuccessAt: Long? = now - 500L,
        captureError: String? = null,
        droppedEvents: Int = 0,
        musicActive: Boolean = false,
        live: LiveTrack? = null,
        stockSource: String? = null,
        notificationAccess: Boolean = true,
    ): LiveState = LiveState(
        serviceRunning = serviceRunning,
        lastSuccessAt = lastSuccessAt,
        captureError = captureError,
        droppedEvents = droppedEvents,
        musicActive = musicActive,
        live = live,
        stockSource = stockSource,
        notificationAccess = notificationAccess,
    )

    @Test
    fun `stopped service is not recording and offers start`() {
        val view = deriveCaptureStatus(
            state(serviceRunning = false, lastSuccessAt = now),
            now,
        )

        assertEquals(CaptureStatus.NOT_RECORDING, view.status)
        assertEquals(CaptureAction.START_SERVICE, view.action)
    }

    @Test
    fun `stopped service wins over an old capture error`() {
        val view = deriveCaptureStatus(
            state(serviceRunning = false, captureError = "disk: no space"),
            now,
        )

        assertEquals(CaptureStatus.NOT_RECORDING, view.status)
        assertEquals(CaptureAction.START_SERVICE, view.action)
    }

    @Test
    fun `first launch is not recording before the first successful tick`() {
        val view = deriveCaptureStatus(
            state(lastSuccessAt = null),
            now,
        )

        assertEquals(CaptureStatus.NOT_RECORDING, view.status)
        assertEquals(CaptureAction.OPEN_DIAGNOSTICS, view.action)
        assertTrue(view.detail.orEmpty().contains("Успешных тиков"))
    }

    @Test
    fun `stale tick is not recording`() {
        val view = deriveCaptureStatus(
            state(lastSuccessAt = now - CAPTURE_STALE_AFTER_MS - 1L),
            now,
        )

        assertEquals(CaptureStatus.NOT_RECORDING, view.status)
        assertEquals(CaptureAction.OPEN_DIAGNOSTICS, view.action)
    }

    @Test
    fun `capture error has priority and keeps the error text`() {
        val view = deriveCaptureStatus(
            state(captureError = "disk: no space"),
            now,
        )

        assertEquals(CaptureStatus.ERROR, view.status)
        assertEquals(CaptureAction.OPEN_DIAGNOSTICS, view.action)
        assertTrue(view.detail.orEmpty().contains("disk: no space"))
    }

    @Test
    fun `dropped events make the status degraded`() {
        val view = deriveCaptureStatus(
            state(droppedEvents = 3),
            now,
        )

        assertEquals(CaptureStatus.DEGRADED, view.status)
        assertEquals(CaptureAction.OPEN_DIAGNOSTICS, view.action)
    }

    @Test
    fun `music without a source is degraded`() {
        val view = deriveCaptureStatus(
            state(musicActive = true),
            now,
        )

        assertEquals(CaptureStatus.DEGRADED, view.status)
        assertEquals(CaptureAction.OPEN_DIAGNOSTICS, view.action)
    }

    @Test
    fun `music without a source and without notification access offers the permission`() {
        val view = deriveCaptureStatus(
            state(musicActive = true, notificationAccess = false),
            now,
        )

        assertEquals(CaptureStatus.DEGRADED, view.status)
        assertEquals(CaptureAction.OPEN_NOTIFICATION_SETTINGS, view.action)
    }

    @Test
    fun `healthy capture is recording`() {
        val view = deriveCaptureStatus(state(), now)

        assertEquals(CaptureStatus.RECORDING, view.status)
        assertEquals(CaptureAction.NONE, view.action)
        assertEquals(now - 500L, view.lastSuccessAt)
    }

    @Test
    fun `idle without notification access is still recording`() {
        val view = deriveCaptureStatus(
            state(notificationAccess = false),
            now,
        )

        assertEquals(CaptureStatus.RECORDING, view.status)
    }

    @Test
    fun `stock source counts as an available source`() {
        val view = deriveCaptureStatus(
            state(musicActive = true, stockSource = "fyt_service"),
            now,
        )

        assertEquals(CaptureStatus.RECORDING, view.status)
    }
}
