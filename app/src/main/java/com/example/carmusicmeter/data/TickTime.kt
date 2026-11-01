package com.example.carmusicmeter.data

/**
 * Два времени одного тика: настенные часы для `ts` и монотонные для длительностей.
 *
 * Настенные часы магнитолы могут синхронизироваться и прыгать; всё, что связано
 * с `playedMs`, позицией и grace-интервалами, должно считать по `monoMs`.
 */
data class TickTime(
    val wallMs: Long,
    val monoMs: Long,
)
