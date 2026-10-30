package com.example.carmusicmeter.probe

import org.json.JSONArray
import org.json.JSONObject

/** Последние медиа-уведомления: нужны для снимка и резервного разбора. */
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
