package com.example.carmusicmeter.data

/** Поля события `stock_player_ui`: структура окна и решение парсера. */
data class StockPlayerUiEvent(
    val window: AccessibilityWindow,
    val windows: List<AccessibilityWindow>,
    val result: StockPlayerParser.AccessibilityParseResult,
) {
    fun toFields(): Map<String, Any?> = linkedMapOf(
        "package" to window.packageName,
        "windowTitle" to window.title,
        "active" to window.active,
        "focused" to window.focused,
        "allTexts" to window.texts(),
        "nodes" to window.nodes.take(MAX_NODES).map { it.toFields() },
        "windows" to windows.map { it.toFields() },
        "accepted" to result.accepted,
        "rejectReason" to result.rejectReason,
        "confidence" to result.confidence,
        "title" to result.state?.title,
        "artist" to result.state?.artist,
        "durationMs" to result.state?.durationMs,
        "positionMs" to result.state?.positionMs,
    )

    private fun AccessibilityNode.toFields(): Map<String, Any?> = mapOf(
        "className" to className,
        "viewId" to viewId,
        "text" to text,
        "contentDescription" to contentDescription,
        "bounds" to bounds,
        "range" to range?.let {
            mapOf("min" to it.min, "max" to it.max, "current" to it.current)
        },
    )

    private fun AccessibilityWindow.toFields(): Map<String, Any?> = mapOf(
        "package" to packageName,
        "title" to title,
        "active" to active,
        "focused" to focused,
        "texts" to texts(20),
    )

    private companion object {
        const val MAX_NODES = 60
    }
}
