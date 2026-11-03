package com.example.carmusicmeter.data

/**
 * Заставка показывается один раз на процесс приложения.
 *
 * Флаг живёт в процессе: возврат из другого приложения и поворот экрана не
 * запускают анимацию повторно, а новый процесс — в том числе после вытеснения
 * системой или перезапуска из лаунчера — показывает её снова.
 */
object LaunchSplashGate {

    @Volatile
    private var shown = false

    fun shouldShow(): Boolean {
        if (shown) return false
        shown = true
        return true
    }

    fun reset() {
        shown = false
    }
}
