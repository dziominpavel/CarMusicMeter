package com.example.carmusicmeter.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.carmusicmeter.CarMusicMeterApp
import com.example.carmusicmeter.data.AccessibilityConnection
import com.example.carmusicmeter.data.AccessibilityNode
import com.example.carmusicmeter.data.AccessibilityRange
import com.example.carmusicmeter.data.AccessibilityWindow
import com.example.carmusicmeter.data.AccessibilityWindowSelector
import com.example.carmusicmeter.data.CaptureGap
import com.example.carmusicmeter.data.CaptureGapPolicy
import com.example.carmusicmeter.data.DebounceGate
import com.example.carmusicmeter.data.ListeningRepository
import com.example.carmusicmeter.data.LogEvent
import com.example.carmusicmeter.data.StockPlayerBridge
import com.example.carmusicmeter.data.StockPlayerParser
import com.example.carmusicmeter.data.StockPlayerProtocol
import com.example.carmusicmeter.data.StockPlayerUiEvent
import com.example.carmusicmeter.data.StockPlayerUiStore
import com.example.carmusicmeter.data.UiLogThrottle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Читает видимое окно штатного плеера через accessibility.
 *
 * Сервис не выполняет клики, скроллинг и ввод. Он перебирает все видимые окна
 * (включая split-screen), стабилизирует переходный UI и пишет диагностику,
 * по которой можно уточнить view id и формат прогресса.
 */
class StockPlayerAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val debounce = DebounceGate(DEBOUNCE_MS)
    private val uiThrottle = UiLogThrottle(UI_LOG_INTERVAL_MS)

    private var lastAcceptedSignature: String? = null
    private var lastScanAt = 0L
    private var gapActive = false

    private val scanRunnable = Runnable { scan() }
    private val pollRunnable = Runnable { scan() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val now = SystemClock.elapsedRealtime()
        AccessibilityConnection.onConnected(now)
        append(
            LogEvent(
                ts = System.currentTimeMillis(),
                type = "accessibility_connected",
                fields = linkedMapOf(
                    "package" to packageName,
                    "component" to "${javaClass.name}",
                ),
            ),
        )
        schedulePoll(INITIAL_POLL_MS)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityConnection.onDisconnected()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        scan()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AccessibilityConnection.onDisconnected()
        handler.removeCallbacksAndMessages(null)
        ioScope.cancel()
        super.onDestroy()
    }

    private fun scan() {
        val now = SystemClock.elapsedRealtime()
        AccessibilityConnection.onScan(now)
        if (now - lastScanAt < MIN_SCAN_GAP_MS) return
        lastScanAt = now

        val audio = getSystemService(AudioManager::class.java)
        val musicActive = audio?.isMusicActive == true
        val windows = collectWindows()
        val selected = AccessibilityWindowSelector.select(windows)

        if (selected == null) {
            val sourceActive = StockPlayerBridge.activeState(now, musicActive) != null
            when (CaptureGapPolicy.action(musicActive, gapActive, sourceActive)) {
                CaptureGapPolicy.Action.OPEN_WINDOW_UNAVAILABLE -> {
                    gapActive = true
                    logGap(active = true, reason = "stock_window_unavailable", windows = windows)
                }

                CaptureGapPolicy.Action.CLOSE_MUSIC_INACTIVE -> {
                    gapActive = false
                    logGap(active = false, reason = "music_inactive", windows = windows)
                }

                CaptureGapPolicy.Action.CLOSE_SOURCE_AVAILABLE -> {
                    gapActive = false
                    logGap(active = false, reason = "source_available", windows = windows)
                }

                null -> Unit
            }
            // Окна плеера нет: не крутим дорогой скан каждые 2 секунды.
            // Появление окна всё равно даст accessibility-событие.
            schedulePoll(IDLE_POLL_MS)
            return
        }

        if (gapActive) {
            gapActive = false
            logGap(active = false, reason = "stock_window_visible", windows = windows)
        }

        // Окно плеера видимо: даже если это список файлов, музыка может играть.
        // Не даём последнему известному треку истечь, пока окно не пропало.
        StockPlayerBridge.touch(now)

        val signature = selected.signature()
        if (signature == lastAcceptedSignature) {
            schedulePoll(if (musicActive) ACTIVE_POLL_MS else IDLE_POLL_MS)
            return
        }

        if (!debounce.offer(signature, now)) {
            scheduleScan(DEBOUNCE_MS)
            schedulePoll(if (musicActive) ACTIVE_POLL_MS else IDLE_POLL_MS)
            return
        }

        val nowWall = System.currentTimeMillis()
        val result = StockPlayerParser.inspectAccessibility(
            packageName = selected.packageName,
            window = selected,
            playing = musicActive,
            nowMs = nowWall,
            nowMonoMs = now,
        )
        StockPlayerUiStore.update(
            window = selected,
            accepted = result.accepted,
            rejectReason = result.rejectReason,
            confidence = result.confidence,
            title = result.state?.title,
            artist = result.state?.artist,
            durationMs = result.state?.durationMs,
            positionMs = result.state?.positionMs,
        )
        if (uiThrottle.shouldLog(signature, now)) {
            logUi(selected, windows, result, nowWall)
        }
        if (result.accepted && result.state != null) {
            StockPlayerBridge.update(result.state)
            lastAcceptedSignature = signature
            maybeStartWatch()
        }

        schedulePoll(if (musicActive) ACTIVE_POLL_MS else IDLE_POLL_MS)
    }

    private fun scheduleScan(delayMs: Long) {
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, delayMs)
    }

    private fun schedulePoll(delayMs: Long) {
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, delayMs)
    }

    private fun maybeStartWatch() {
        val app = applicationContext as? CarMusicMeterApp ?: return
        if (app.prefs.autoStart && !ListeningRepository.state.value.serviceRunning) {
            runCatching {
                WatchServiceController.start(this, StartSource.ACCESSIBILITY)
            }
        }
    }

    private fun collectWindows(): List<AccessibilityWindow> {
        val result = mutableListOf<AccessibilityWindow>()
        val source = runCatching { windows }.getOrNull().orEmpty()
        for (window in source) {
            val root = window.root ?: continue
            val packageName = root.packageName?.toString() ?: continue
            if (!StockPlayerProtocol.isStockPackage(packageName)) continue
            val nodes = collectNodes(root)
            if (nodes.isEmpty()) continue
            result += AccessibilityWindow(
                packageName = packageName,
                title = window.title?.toString(),
                active = window.isActive,
                focused = window.isFocused,
                nodes = nodes,
            )
        }
        return result
    }

    private fun collectNodes(root: AccessibilityNodeInfo): List<AccessibilityNode> {
        val out = mutableListOf<AccessibilityNode>()
        visit(root, out, 0)
        return out
    }

    private fun visit(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNode>, depth: Int) {
        if (out.size >= MAX_NODES || depth > MAX_DEPTH) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim()?.take(MAX_TEXT)
            val description = node.contentDescription?.toString()?.trim()?.take(MAX_TEXT)
            val range = node.rangeInfo?.let {
                AccessibilityRange(
                    min = it.min,
                    max = it.max,
                    current = it.current,
                )
            }
            val viewId = node.viewIdResourceName
            if (text != null || description != null || range != null || viewId != null) {
                val bounds = Rect()
                runCatching { node.getBoundsInScreen(bounds) }
                out += AccessibilityNode(
                    className = node.className?.toString(),
                    viewId = viewId,
                    text = text,
                    contentDescription = description,
                    range = range,
                    bounds = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
                )
            }
        }
        for (index in 0 until node.childCount) {
            if (out.size >= MAX_NODES) return
            node.getChild(index)?.let { child -> visit(child, out, depth + 1) }
        }
    }

    private fun logUi(
        window: AccessibilityWindow,
        windows: List<AccessibilityWindow>,
        result: StockPlayerParser.AccessibilityParseResult,
        nowWall: Long,
    ) {
        val fields = StockPlayerUiEvent(window, windows, result).toFields()
        append(LogEvent(nowWall, "stock_player_ui", fields))
    }

    private fun logGap(active: Boolean, reason: String, windows: List<AccessibilityWindow>) {
        val gap = CaptureGap(
            active = active,
            reason = reason,
            last = StockPlayerBridge.latest(),
            isMusicActive = getSystemService(AudioManager::class.java)?.isMusicActive == true,
            windows = windows,
        )
        append(LogEvent(System.currentTimeMillis(), "capture_gap", gap.toFields()))
    }

    private fun append(event: LogEvent) {
        val app = applicationContext as? CarMusicMeterApp ?: return
        ioScope.launch {
            runCatching {
                app.eventLog.append(event)
                ListeningRepository.record(listOf(event))
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 800L
        const val UI_LOG_INTERVAL_MS = 2_000L
        const val MIN_SCAN_GAP_MS = 100L
        const val ACTIVE_POLL_MS = 2_000L
        const val IDLE_POLL_MS = 5_000L
        const val INITIAL_POLL_MS = 500L
        const val MAX_NODES = 60
        const val MAX_DEPTH = 16
        const val MAX_TEXT = 200
    }
}
