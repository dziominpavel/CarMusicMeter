package com.example.carmusicmeter.data

import org.json.JSONArray
import org.json.JSONObject

/** Диапазон прогресса, снятый с узла accessibility. */
data class AccessibilityRange(
    val min: Float,
    val max: Float,
    val current: Float,
)

/** Один видимый узел окна штатного плеера. */
data class AccessibilityNode(
    val className: String? = null,
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val range: AccessibilityRange? = null,
    val bounds: String? = null,
)

/**
 * Снимок видимого окна штатного плеера.
 *
 * Нужен и парсеру, и диагностике: парсер берёт тексты/диапазоны, а снимок
 * сохраняет структуру, по которой можно уточнить view id и формат прогресса.
 */
data class AccessibilityWindow(
    val packageName: String,
    val title: String? = null,
    val active: Boolean = false,
    val focused: Boolean = false,
    val nodes: List<AccessibilityNode> = emptyList(),
) {
    fun texts(limit: Int = 80): List<String> {
        val seen = LinkedHashSet<String>()
        for (node in nodes) {
            for (value in listOf(node.text, node.contentDescription)) {
                val normalized = value?.trim()?.replace(Regex("""\s+"""), " ")
                if (!normalized.isNullOrEmpty() && seen.size < limit) {
                    seen.add(normalized)
                }
            }
        }
        return seen.toList()
    }

    fun ranges(): List<AccessibilityRange> = nodes.mapNotNull { it.range }

    /** Подпись содержимого без active/focused, чтобы фокус не сбрасывал debounce. */
    fun signature(): String = buildString {
        append(packageName)
        append('|')
        append(title.orEmpty())
        nodes.forEach { node ->
            append('|')
            append(node.className.orEmpty())
            append(':')
            append(node.viewId.orEmpty())
            append(':')
            append(node.text.orEmpty())
            append(':')
            append(node.contentDescription.orEmpty())
            append(':')
            node.range?.let { range ->
                append(range.min)
                append('/')
                append(range.max)
                append('/')
                append(range.current)
            }
        }
    }
}

object AccessibilityWindowSelector {

    /** Активное окно важнее сфокусированного, оно же важнее просто видимого. */
    fun select(windows: List<AccessibilityWindow>): AccessibilityWindow? =
        windows
            .filter { it.nodes.isNotEmpty() }
            .maxWithOrNull(
                compareBy<AccessibilityWindow> { it.active }
                    .thenBy { it.focused }
                    .thenBy { it.nodes.size },
            )
}

/**
 * Пропускает подпись только после того, как она продержалась [delayMs].
 * Так переходный экран при открытии плеера не создаёт ложный трек.
 */
class DebounceGate(private val delayMs: Long = 800L) {

    private var signature: String? = null
    private var sinceMonoMs: Long = 0L

    fun offer(signature: String, nowMonoMs: Long): Boolean {
        if (this.signature != signature) {
            this.signature = signature
            this.sinceMonoMs = nowMonoMs
            return false
        }
        return nowMonoMs - sinceMonoMs >= delayMs
    }

    fun reset() {
        signature = null
        sinceMonoMs = 0L
    }
}

/** Ограничивает запись диагностики UI: не чаще [minIntervalMs] и только при смене. */
class UiLogThrottle(private val minIntervalMs: Long = 2_000L) {

    private var lastSignature: String? = null
    private var lastAtMonoMs: Long = 0L

    fun shouldLog(signature: String, nowMonoMs: Long): Boolean {
        if (signature == lastSignature) return false
        if (lastSignature != null && nowMonoMs - lastAtMonoMs < minIntervalMs) {
            return false
        }
        lastSignature = signature
        lastAtMonoMs = nowMonoMs
        return true
    }

    fun reset() {
        lastSignature = null
        lastAtMonoMs = 0L
    }
}

fun AccessibilityNode.toJson(): JSONObject = JSONObject().apply {
    put("className", className ?: JSONObject.NULL)
    put("viewId", viewId ?: JSONObject.NULL)
    put("text", text ?: JSONObject.NULL)
    put("contentDescription", contentDescription ?: JSONObject.NULL)
    put("bounds", bounds ?: JSONObject.NULL)
    put("range", range?.let {
        JSONObject().apply {
            put("min", it.min.toDouble())
            put("max", it.max.toDouble())
            put("current", it.current.toDouble())
        }
    } ?: JSONObject.NULL)
}

fun AccessibilityWindow.toJson(): JSONObject = JSONObject().apply {
    put("package", packageName)
    put("title", title ?: JSONObject.NULL)
    put("active", active)
    put("focused", focused)
    put("texts", JSONArray(texts()))
    put("nodes", JSONArray().apply { nodes.forEach { put(it.toJson()) } })
}

fun List<AccessibilityWindow>.toJson(): JSONArray = JSONArray().apply {
    forEach { put(it.toJson()) }
}

/**
 * Последняя диагностика окна штатного плеера. Пишется accessibility-сервисом,
 * читается снимком; не является источником текущего трека.
 */
object StockPlayerUiStore {

    @Volatile
    private var window: AccessibilityWindow? = null

    @Volatile
    private var accepted: Boolean? = null

    @Volatile
    private var rejectReason: String? = null

    @Volatile
    private var confidence: String? = null

    @Volatile
    private var title: String? = null

    @Volatile
    private var artist: String? = null

    @Volatile
    private var durationMs: Long? = null

    @Volatile
    private var positionMs: Long? = null

    fun update(
        window: AccessibilityWindow,
        accepted: Boolean,
        rejectReason: String?,
        confidence: String?,
        title: String?,
        artist: String?,
        durationMs: Long?,
        positionMs: Long?,
    ) {
        this.window = window
        this.accepted = accepted
        this.rejectReason = rejectReason
        this.confidence = confidence
        this.title = title
        this.artist = artist
        this.durationMs = durationMs
        this.positionMs = positionMs
    }

    fun latest(): AccessibilityWindow? = window

    fun toJson(): JSONObject = JSONObject().apply {
        put("window", window?.toJson() ?: JSONObject.NULL)
        put("accepted", accepted ?: JSONObject.NULL)
        put("rejectReason", rejectReason ?: JSONObject.NULL)
        put("confidence", confidence ?: JSONObject.NULL)
        put("title", title ?: JSONObject.NULL)
        put("artist", artist ?: JSONObject.NULL)
        put("durationMs", durationMs ?: JSONObject.NULL)
        put("positionMs", positionMs ?: JSONObject.NULL)
    }

    fun reset() {
        window = null
        accepted = null
        rejectReason = null
        confidence = null
        title = null
        artist = null
        durationMs = null
        positionMs = null
    }
}
