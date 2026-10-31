package com.example.carmusicmeter.probe

import org.json.JSONArray
import org.json.JSONObject

/**
 * Последние уведомления в памяти. В снимок попадают все, с признаком
 * `mediaLike`; в журнал по-прежнему пишутся только медиаподобные.
 */
object NotificationCache {

    private const val LIMIT = 40
    private val items = ArrayDeque<JSONObject>()

    @Synchronized
    fun add(item: JSONObject) {
        items.addLast(item)
        while (items.size > LIMIT) items.removeFirst()
    }

    @Synchronized
    fun toJson(): JSONArray = JSONArray().apply {
        items.forEach { put(it) }
    }

    @Synchronized
    fun clear() {
        items.clear()
    }
}
