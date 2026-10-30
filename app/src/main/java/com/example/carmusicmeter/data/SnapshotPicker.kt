package com.example.carmusicmeter.data

/**
 * Выбирает сессию, которую считаем «сейчас играет».
 *
 * Приоритет:
 * 1. текущий пакет, если он всё ещё играет;
 * 2. любой играющий пакет;
 * 3. текущий пакет в паузе;
 * 4. первая сессия с названием трека.
 *
 * Сессии без названия не трекаем: по ним нельзя понять, что это за трек,
 * но они всё равно попадут в полный дамп снимка.
 */
object SnapshotPicker {

    fun pick(snapshots: List<MediaSnapshot>, currentPackage: String?): MediaSnapshot? {
        if (snapshots.isEmpty()) return null
        val withTitle = snapshots.filter { it.hasTitle }
        if (withTitle.isEmpty()) return null
        val candidates = withTitle

        if (currentPackage != null) {
            candidates.firstOrNull {
                it.packageName == currentPackage && it.phase == MediaPhase.PLAYING
            }?.let { return it }
        }
        candidates.firstOrNull { it.phase == MediaPhase.PLAYING }?.let { return it }
        if (currentPackage != null) {
            candidates.firstOrNull { it.packageName == currentPackage }?.let { return it }
        }
        return candidates.firstOrNull { it.phase == MediaPhase.PAUSED } ?: candidates.first()
    }
}
