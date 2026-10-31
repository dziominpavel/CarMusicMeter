package com.example.carmusicmeter.data

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

class Prefs(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Автозапуск службы после загрузки магнитолы и при появлении музыки. */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) {
            prefs.edit { putBoolean(KEY_AUTO_START, value) }
        }

    /** 0 — хранить журнал бессрочно. */
    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION_DAYS, 0)
        set(value) {
            prefs.edit { putInt(KEY_RETENTION_DAYS, value) }
        }

    /** 0 — без лимита объёма. */
    var maxBytes: Long
        get() = prefs.getLong(KEY_MAX_BYTES, StoragePolicy.DEFAULT_MAX_BYTES)
        set(value) {
            prefs.edit { putLong(KEY_MAX_BYTES, value) }
        }

    var exportRange: ExportRange
        get() = ExportRange.fromTag(prefs.getString(KEY_EXPORT_RANGE, null))
        set(value) {
            prefs.edit { putString(KEY_EXPORT_RANGE, value.tag) }
        }

    fun storagePolicy(): StoragePolicy = StoragePolicy(
        retentionDays = retentionDays,
        maxBytes = maxBytes,
    )

    /**
     * Идентификатор устройства для `manifest.json`. Создаётся один раз;
     * сбрасывается только при очистке данных приложения.
     */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: synchronized(this) {
            prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also { id ->
                prefs.edit(commit = true) { putString(KEY_DEVICE_ID, id) }
            }
        }

    /** Номер выгрузки: увеличивается при каждом старте экспорта. */
    fun nextExportSeq(): Long = synchronized(this) {
        val next = prefs.getLong(KEY_EXPORT_SEQ, 0L) + 1
        prefs.edit(commit = true) { putLong(KEY_EXPORT_SEQ, next) }
        next
    }

    private companion object {
        const val KEY_AUTO_START = "auto_start"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_EXPORT_SEQ = "export_seq"
        const val KEY_RETENTION_DAYS = "retention_days"
        const val KEY_MAX_BYTES = "max_bytes"
        const val KEY_EXPORT_RANGE = "export_range"
    }
}
