package com.example.carmusicmeter.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

enum class StartSource(val value: String) {
    MANUAL("manual"),
    BOOT("boot"),
    NOTIFICATION("notification"),
    ACCESSIBILITY("accessibility"),
    APP_OPEN("app_open"),
    JOB("job"),
    WATCHDOG("watchdog"),
    MEDIA_MOUNTED("media_mounted"),
    TEYES_AUTOSTART("teyes_autostart"),
    FYT_SERVICE("fyt_service"),
}

object WatchServiceController {

    const val EXTRA_SOURCE = "start_source"

    fun start(context: Context, source: StartSource) {
        val intent = Intent(context, MediaWatchService::class.java)
            .putExtra(EXTRA_SOURCE, source.value)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val stop = Intent(context, MediaWatchService::class.java)
            .setAction(MediaWatchService.ACTION_STOP)
        val started = runCatching { context.startService(stop) }.isSuccess
        if (!started) {
            context.stopService(Intent(context, MediaWatchService::class.java))
        }
    }
}
