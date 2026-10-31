package com.example.carmusicmeter.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.carmusicmeter.CarMusicMeterApp
import com.example.carmusicmeter.autostart.AutostartScheduler
import com.example.carmusicmeter.data.LogEvent
import com.example.carmusicmeter.service.StartSource
import com.example.carmusicmeter.service.WatchServiceController

/**
 * Будит службу после загрузки, обновления приложения или монтирования флешки.
 *
 * Прошивка Teyes может не отдавать BOOT_COMPLETED без своего whitelist,
 * поэтому рядом работают persisted JobScheduler и отдельная activity
 * «CarMusicMeter (автозапуск)».
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

        AutostartScheduler.sync(context, enabled = true)
        val source = if (action == Intent.ACTION_MEDIA_MOUNTED) {
            StartSource.MEDIA_MOUNTED
        } else {
            StartSource.BOOT
        }
        runCatching { WatchServiceController.start(context, source) }
            .onFailure { error ->
                app.eventLog.append(
                    LogEvent(
                        System.currentTimeMillis(),
                        "service_start_failed",
                        mapOf("error" to error.message, "source" to source.value),
                    ),
                )
            }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_MEDIA_MOUNTED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
