package com.example.carmusicmeter.data

import org.json.JSONArray
import org.json.JSONObject

/** Преобразование Kotlin-значений в JSON без сторонних библиотек. */
object Json {

    fun eventToJson(
        event: LogEvent,
        bootId: String? = null,
        monoMs: Long? = null,
        clockVerified: Boolean? = null,
    ): String = JSONObject().apply {
        put("ts", event.ts)
        put("type", event.type)
        if (bootId != null) put("bootId", bootId)
        if (monoMs != null) put("monoMs", monoMs)
        if (clockVerified != null) put("clockVerified", clockVerified)
        event.fields.forEach { (key, value) -> put(key, toJsonValue(value)) }
    }.toString()

    fun objectFrom(
        fields: Map<String, Any?>,
        ts: Long? = null,
        type: String? = null,
    ): JSONObject = JSONObject().apply {
        if (ts != null) put("ts", ts)
        if (type != null) put("type", type)
        fields.forEach { (key, value) -> put(key, toJsonValue(value)) }
    }

    fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (key, item) ->
                if (key is String) put(key, toJsonValue(item))
            }
        }
        is Iterable<*> -> JSONArray().apply {
            value.forEach { put(toJsonValue(it)) }
        }
        is Array<*> -> JSONArray().apply {
            value.forEach { put(toJsonValue(it)) }
        }
        is Int, is Long, is Float, is Double, is Boolean, is String -> value
        is Enum<*> -> value.name
        else -> value.toString()
    }
}
