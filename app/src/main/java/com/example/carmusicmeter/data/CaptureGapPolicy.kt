package com.example.carmusicmeter.data

/**
 * Решает, что делать с gap окна штатного плеера.
 *
 * `capture_gap` — это потеря сбора, а не просто скрытое окно. Если другой
 * источник (например, `fyt_service`) свежий, gap не открываем.
 */
object CaptureGapPolicy {

    enum class Action {
        OPEN_WINDOW_UNAVAILABLE,
        CLOSE_MUSIC_INACTIVE,
        CLOSE_SOURCE_AVAILABLE,
    }

    fun action(
        musicActive: Boolean,
        gapActive: Boolean,
        sourceActive: Boolean,
    ): Action? = when {
        sourceActive && gapActive -> Action.CLOSE_SOURCE_AVAILABLE
        sourceActive -> null
        musicActive && !gapActive -> Action.OPEN_WINDOW_UNAVAILABLE
        !musicActive && gapActive -> Action.CLOSE_MUSIC_INACTIVE
        else -> null
    }
}
