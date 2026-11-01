package com.example.carmusicmeter.data

import kotlin.math.abs

/** Расхождение настенных и монотонных часов между двумя тиками. */
data class ClockJump(
    val wallDeltaMs: Long,
    val monoDeltaMs: Long,
) {
    val driftMs: Long get() = wallDeltaMs - monoDeltaMs
}

object ClockJumpDetector {

    const val THRESHOLD_MS = 5_000L

    fun detect(
        previousWallMs: Long?,
        previousMonoMs: Long?,
        wallMs: Long,
        monoMs: Long,
    ): ClockJump? {
        if (previousWallMs == null || previousMonoMs == null) return null
        val jump = ClockJump(
            wallDeltaMs = wallMs - previousWallMs,
            monoDeltaMs = monoMs - previousMonoMs,
        )
        return jump.takeIf { abs(it.driftMs) >= THRESHOLD_MS }
    }
}
