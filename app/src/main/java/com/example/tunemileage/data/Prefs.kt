package com.example.tunemileage.data

import android.content.Context

class Prefs(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Автозапуск службы после загрузки магнитолы и при появлении музыки. */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_START, value).apply()
        }

    private companion object {
        const val KEY_AUTO_START = "auto_start"
    }
}
