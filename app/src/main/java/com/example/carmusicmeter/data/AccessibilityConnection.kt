package com.example.carmusicmeter.data

/**
 * Фактическое состояние accessibility-сервиса.
 *
 * Системная настройка может быть включена, а сам сервис — убит. Поэтому
 * `connected` считается живым только по heartbeat последнего скана.
 */
object AccessibilityConnection {

    private const val HEARTBEAT_MS = 10_000L

    @Volatile
    private var connectedAtMs: Long? = null

    @Volatile
    private var lastScanAtMs: Long? = null

    fun onConnected(nowMonoMs: Long) {
        connectedAtMs = nowMonoMs
        lastScanAtMs = nowMonoMs
    }

    fun onScan(nowMonoMs: Long) {
        if (connectedAtMs != null) {
            lastScanAtMs = nowMonoMs
        }
    }

    fun onDisconnected() {
        connectedAtMs = null
        lastScanAtMs = null
    }

    fun isConnected(nowMonoMs: Long): Boolean {
        val scan = lastScanAtMs ?: return false
        if (connectedAtMs == null) return false
        return nowMonoMs - scan <= HEARTBEAT_MS
    }

    fun reset() {
        onDisconnected()
    }
}
