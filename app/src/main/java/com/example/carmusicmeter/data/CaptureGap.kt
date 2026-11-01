package com.example.carmusicmeter.data

/**
 * Разрыв сбора штатного плеера: окно пропало или вернулось.
 * Хранит ровно те поля, по которым отчёт отличает потерю источника от скипа.
 */
data class CaptureGap(
    val active: Boolean,
    val reason: String,
    val last: StockPlayerState?,
    val isMusicActive: Boolean,
    val windows: List<AccessibilityWindow>,
) {
    fun toFields(): Map<String, Any?> = linkedMapOf(
        "active" to active,
        "reason" to reason,
        "lastPackage" to last?.packageName,
        "lastTitle" to last?.title,
        "lastArtist" to last?.artist,
        "lastSource" to last?.source,
        "isMusicActive" to isMusicActive,
        "windows" to windows.map {
            mapOf(
                "package" to it.packageName,
                "title" to it.title,
                "active" to it.active,
                "focused" to it.focused,
                "texts" to it.texts(20),
            )
        },
    )
}
