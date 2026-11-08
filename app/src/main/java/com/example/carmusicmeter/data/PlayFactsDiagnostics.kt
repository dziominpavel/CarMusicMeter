package com.example.carmusicmeter.data

/** Счётчики фактов для диагностики: что записано, что построено и что сломано. */
data class PlayFactsDiagnostics(
    val live: Int = 0,
    val history: Int = 0,
    val open: Int = 0,
    val cacheDays: Int = 0,
    val builderVersion: Int = PlayBuilder.VERSION,
    val overlaps: Int = 0,
    val errors: Int = 0,
)
