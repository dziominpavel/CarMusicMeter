package com.example.tunemileage.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.tunemileage.TuneMileageApp
import com.example.tunemileage.data.LogEvent
import com.example.tunemileage.service.WatchServiceController

/**
 * Автозапуск после загрузки магнитолы. Прошивка Teyes выключает питание
 * вместе с зажиганием, поэтому без этого служба стартовала бы только
 * после ручного открытия приложения.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in ACTIONS) return

        val app = context.applicationContext as TuneMileageApp
        val now = System.currentTimeMillis()
        app.eventLog.append(
            LogEvent(now, "boot_received", mapOf("action" to action)),
        )
        if (!app.prefs.autoStart) return

        runCatching { WatchServiceController.start(context) }
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
