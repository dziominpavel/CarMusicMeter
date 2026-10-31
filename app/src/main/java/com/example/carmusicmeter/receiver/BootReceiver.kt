package com.example.carmusicmeter.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.carmusicmeter.CarMusicMeterApp
import com.example.carmusicmeter.data.LogEvent
import com.example.carmusicmeter.service.StartSource
import com.example.carmusicmeter.service.WatchServiceController

/**
 * Автозапуск после загрузки магнитолы. Прошивка Teyes выключает питание
 * вместе с зажиганием, поэтому без этого служба стартовала бы только
 * после ручного открытия приложения.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in ACTIONS) return

        val app = context.applicationContext as CarMusicMeterApp
        val now = System.currentTimeMillis()
        app.eventLog.append(
            LogEvent(now, "boot_received", mapOf("action" to action)),
        )
        if (!app.prefs.autoStart) return

        runCatching { WatchServiceController.start(context, StartSource.BOOT) }
            .onFailure { error ->
                app.eventLog.append(
                    LogEvent(
                        System.currentTimeMillis(),
                        "service_start_failed",
                        mapOf("error" to error.message, "source" to "boot"),
                    ),
                )
            }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
