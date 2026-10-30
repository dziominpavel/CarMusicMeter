package com.example.carmusicmeter.data

/**
 * Одно событие журнала. Пишется в JSONL как {"ts":..., "type":..., ...fields}.
 * Никаких Android-зависимостей: трекер и события тестируются на JVM.
 */
data class LogEvent(
    val ts: Long,
    val type: String,
    val fields: Map<String, Any?> = emptyMap(),
)
