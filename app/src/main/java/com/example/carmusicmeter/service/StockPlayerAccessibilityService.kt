package com.example.carmusicmeter.service

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.carmusicmeter.CarMusicMeterApp
import com.example.carmusicmeter.data.ListeningRepository
import com.example.carmusicmeter.data.StockPlayerBridge
import com.example.carmusicmeter.data.StockPlayerParser
import com.example.carmusicmeter.data.StockPlayerProtocol

/**
 * Необязательный резервный источник: читает только текст открытого окна
 * штатного плеера. Никаких кликов, скроллинга и изменения настроек.
 *
 * Включается вручную в спецдоступе Android. Если выключен, приложение
 * работает через MediaSession, broadcast и уведомления.
 */
class StockPlayerAccessibilityService : AccessibilityService() {

    private var lastSignature: String? = null
    private var lastScanAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (!StockPlayerProtocol.isStockPackage(packageName)) return
        if (event.eventType !in EVENTS) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastScanAt < SCAN_INTERVAL_MS) return
        lastScanAt = now

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != packageName) return
        val texts = collectTexts(root)
        if (texts.isEmpty()) return
        val signature = texts.joinToString("|")
        if (signature == lastSignature) return
        lastSignature = signature

        val audio = getSystemService(AudioManager::class.java)
        val state = StockPlayerParser.fromAccessibility(
            packageName = packageName,
            texts = texts,
            playing = audio?.isMusicActive == true,
            nowMs = System.currentTimeMillis(),
            nowMonoMs = SystemClock.elapsedRealtime(),
        ) ?: return
        StockPlayerBridge.update(state)

        val app = applicationContext as? CarMusicMeterApp ?: return
        if (app.prefs.autoStart && !ListeningRepository.state.value.serviceRunning) {
            runCatching {
                WatchServiceController.start(this, StartSource.ACCESSIBILITY)
            }
        }
    }

    override fun onInterrupt() = Unit

    private fun collectTexts(root: AccessibilityNodeInfo): List<String> {
        val out = LinkedHashSet<String>()
        visit(root, out, 0)
        return out.map { it.take(120) }.take(20)
    }

    private fun visit(node: AccessibilityNodeInfo, out: MutableSet<String>, depth: Int) {
        if (depth > MAX_DEPTH || out.size >= MAX_TEXTS) return
        if (node.isVisibleToUser) {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> visit(child, out, depth + 1) }
        }
    }

    private companion object {
        const val SCAN_INTERVAL_MS = 400L
        const val MAX_DEPTH = 12
        const val MAX_TEXTS = 20
        val EVENTS = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        )
    }
}
