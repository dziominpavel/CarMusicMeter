package com.example.carmusicmeter.data

/**
 * Границы разрыва «музыка играет, источника нет».
 *
 * Возвращает true на старте разрыва и false на восстановлении, чтобы событие
 * писалась один раз на границу, а не на каждом тике.
 */
class NoSourceGapTracker {

    private var active = false

    fun update(noSource: Boolean): Boolean? = when {
        noSource && !active -> {
            active = true
            true
        }
        !noSource && active -> {
            active = false
            false
        }
        else -> null
    }

    fun isActive(): Boolean = active
}
