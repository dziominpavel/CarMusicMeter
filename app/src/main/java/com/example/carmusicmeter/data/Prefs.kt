package com.example.carmusicmeter.data

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Автозапуск службы после загрузки магнитолы и при появлении музыки. */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) {
            prefs.edit { putBoolean(KEY_AUTO_START, value) }
        }

    private companion object {
        const val KEY_AUTO_START = "auto_start"
    }
}
